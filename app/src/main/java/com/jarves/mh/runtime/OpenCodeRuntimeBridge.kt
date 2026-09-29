package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [RuntimeBridge] driving the official OpenCode CLI through the Agent Client Protocol:
 * `opencode acp`.
 *
 * ACP is newline-delimited JSON-RPC 2.0 over the child's stdin/stdout, configured by every ACP
 * editor as `{"command":"opencode","args":["acp"]}`. That is the same transport the DeepSeek
 * Harness SDK profile uses, so this bridge reuses the same launch path and output-tail loop.
 *
 * Why the genuine client matters: OpenCode Zen's free models are refused to arbitrary HTTP
 * clients with `403 "OpenCode's free tier can only be used from within OpenCode"`. Only the
 * real `opencode` binary is accepted, so this bridge never calls Zen itself and never needs an
 * API key for the free catalog. Any paid provider is configured by the CLI in-guest.
 */
class OpenCodeRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()

    /** ACP request id → our approval id, so a UI decision can be sent back on the wire. */
    private val pendingPermissions = ConcurrentHashMap<Any, String>()
    private val approvalToRequestId = ConcurrentHashMap<String, Any>()

    @Volatile private var activeProcess: Process? = null
    @Volatile private var wire: java.io.Writer? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    @Volatile private var lastThinkingUpdateAt: Long = 0L

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        lastThinkingUpdateAt = 0L
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting OpenCode…")

        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                activeProcess?.let { running ->
                    Thread {
                        running.destroy()
                        Thread.sleep(500)
                        if (running.isAlive) running.destroyForcibly()
                    }.start()
                }
            }
            startForegroundRuntime(projectSlug)
            check(installer.isAgentInstalled(AgentKind.OPENCODE)) {
                "OpenCode is not installed. Open Settings → Coding agent to install it."
            }
            val installed = installer.installedRuntime()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind)
            val environment = linkedMapOf(
                "DISABLE_AUTOUPDATER" to "1",
                // Keep the CLI fully non-interactive: every approval is surfaced to the app UI
                // through ACP instead of being answered by a prompt inside the guest.
                "OPENCODE_PERMISSION" to "allow",
            )
            applyProviderEnvironment(environment, provider)

            Log.d("OpenCodeBridge", "Launching opencode acp; provider=${provider.kind.name}")
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                environment,
                listOf("/usr/local/bin/opencode", "acp"),
                guestWorkspacePath = guestWorkspacePath,
                // Keep PRoot's hard-link emulation on: unlike dsh's editor, OpenCode's file
                // tools are not built around atomic temp-file renames.
                emulateHardLinks = true,
            )
            activeProcess = process

            val result = runAcpSession(
                process = process,
                sessionId = sessionId,
                guestWorkspacePath = guestWorkspacePath,
                contextPrompt = contextPrompt,
            )
            val exit = process.waitFor()
            Log.d("OpenCodeBridge", "ACP process exited with code $exit")

            val changed = checkpoints.changedFiles(workspace, before)
            if (changed.isNotEmpty()) {
                checkpoints.saveChangedPaths(projectId, changed)
                val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
            } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                acceptLastChanges(projectId)
            }

            if (exit == 0 && result.completed && !userStopRequested) {
                emitCompletedOnce(sessionId)
            } else {
                if (userStopRequested) throw OpenCodeSessionException("Stopped by user")
                error(result.failure.ifBlank { "OpenCode stopped with exit code $exit" })
            }
        }.onFailure { error ->
            Log.e("OpenCodeBridge", "Session failed", error)
            emitFailureOnce(sessionId, friendlyError(error))
        }
        activeProcess = null
        wire = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    /**
     * OpenCode keeps its own credentials in the guest, so only a generic key is forwarded for
     * the key-based providers. Zen needs nothing: the genuine client reaches the free catalog
     * without any credential.
     */
    private fun applyProviderEnvironment(environment: MutableMap<String, String>, provider: ProviderProfile) {
        if (provider.kind == ProviderKind.OPENCODE_ZEN) return
        val secret = secretFor(provider).orEmpty()
        if (secret.isBlank()) return
        environment["MH_OPENCODE_API_KEY"] = secret
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = withContext(Dispatchers.IO) {
        val requestId = approvalToRequestId.remove(request.approvalId) ?: return@withContext
        pendingPermissions.remove(requestId)
        sendPermissionResult(
            requestId = requestId,
            outcome = if (approved) {
                JSONObject()
                    .put("outcome", "selected")
                    .put("optionId", "allow")
            } else {
                JSONObject()
                    .put("outcome", "cancelled")
            },
        )
        eventBus.emit(
            if (approved) RuntimeEvent.ToolApproved(request.sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(request.sessionId, request.approvalId),
        )
    }

    /**
     * Replies to an inbound ACP request on the child's stdin. Kept as a member so an approval
     * decided on the UI thread can still be written back after the read loop has handed over.
     */
    private fun sendPermissionResult(requestId: Any, outcome: JSONObject) {
        val target = wire ?: return
        runCatching {
            val frame = JSONObject().put("jsonrpc", "2.0").put("id", requestId).put("result", outcome)
            synchronized(target) {
                target.write(frame.toString())
                target.write("\n")
                target.flush()
            }
        }.onFailure { Log.w("OpenCodeBridge", "Could not answer permission request", it) }
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        if (!backup.isDirectory || !File(checkpoint, "changes.json").isFile) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) return@withContext false
        paths.forEach { path ->
            val target = checkpoints.safeWorkspaceFile(workspace, path)
            val original = checkpoints.safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile) {
                target.delete()
            }
        }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.checkpointDir(projectId).deleteRecursively()
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = checkpoints.safeWorkspaceFile(File(checkpoints.checkpointDir(projectId), "project"), path)
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        if (backup.isFile) {
            target.parentFile?.mkdirs()
            backup.copyTo(target, overwrite = true)
            true
        } else if (target.isFile) {
            target.delete()
            true
        } else {
            false
        }
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val remaining = checkpoints.readChangedPaths(projectId) - path
        if (remaining.isEmpty()) {
            checkpoints.checkpointDir(projectId).deleteRecursively()
        } else {
            checkpoints.saveChangedPaths(projectId, remaining)
        }
        true
    }

    /**
     * Drives one ACP session over stdio: initialize → session/new → session/prompt, answering
     * inbound permission requests as the user decides, until the turn reports a stop reason.
     */
    private suspend fun runAcpSession(
        process: Process,
        sessionId: String,
        guestWorkspacePath: String,
        contextPrompt: String,
    ): OpenCodeAcpRunResult {
        val nativeProcess = process as? NativeSpawnProcess
            ?: error("Unsupported Android runtime process")
        val out = process.outputStream.bufferedWriter()
        wire = out
        val parser = OpenCodeAcpProtocolParser()
        var outputOffset = 0L
        val pendingOutput = StringBuilder()
        var nextRequestId = 1
        var acpSessionId = ""
        var promptSent = false
        var promptRequestId: Any? = null
        var completed = false
        var sawActivity = false
        var failure = ""
        val toolTitles = ConcurrentHashMap<String, String>()
        // Unknown session/update kinds are logged once each per turn so an unrecognised ACP
        // event is visible in logcat without flooding it on every chunk.
        val loggedUnknownKinds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        fun writeFrame(frame: JSONObject) {
            synchronized(out) {
                out.write(frame.toString())
                out.newLine()
                out.flush()
            }
        }

        fun send(method: String, params: JSONObject? = null, id: Any? = null) {
            val frame = JSONObject().put("jsonrpc", "2.0").put("method", method)
            if (id != null) frame.put("id", id)
            if (params != null) frame.put("params", params)
            writeFrame(frame)
        }

        fun respond(id: Any, result: JSONObject) {
            writeFrame(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))
        }

        suspend fun handle(event: OpenCodeAcpEvent) {
            when (event) {
                is OpenCodeAcpEvent.Initialized -> if (!promptSent) {
                    send(
                        method = "session/new",
                        id = nextRequestId++,
                        params = JSONObject()
                            .put("cwd", guestWorkspacePath)
                            .put("mcpServers", JSONArray()),
                    )
                }
                is OpenCodeAcpEvent.SessionCreated -> if (acpSessionId.isEmpty() && !promptSent) {
                    acpSessionId = event.acpSessionId
                    Log.d("OpenCodeBridge", "ACP session created: $acpSessionId")
                    promptRequestId = nextRequestId++
                    send(
                        method = "session/prompt",
                        id = promptRequestId,
                        params = JSONObject()
                            .put("sessionId", acpSessionId)
                            .put(
                                "prompt",
                                JSONArray().put(
                                    JSONObject().put("type", "text").put("text", contextPrompt),
                                ),
                            ),
                    )
                    promptSent = true
                }
                is OpenCodeAcpEvent.AgentChunk -> if (event.text.isNotEmpty()) {
                    sawActivity = true
                    if (event.thought) {
                        emitReasoningSummary(
                            sessionId = sessionId,
                            text = event.text,
                            blockId = event.text.hashCode().toLong(),
                            startsNewBlock = false,
                            isFinal = false,
                            force = false,
                        )
                    } else {
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.text))
                    }
                }
                is OpenCodeAcpEvent.ToolUpdate -> {
                    sawActivity = true
                    val label = event.title
                    if (event.toolCallId.isNotEmpty()) toolTitles[event.toolCallId] = label
                    val detail = event.detail.ifBlank { event.kind }
                    when (event.status) {
                        "completed", "failed" -> eventBus.emit(
                            RuntimeEvent.ToolCompleted(sessionId, label, detail.ifBlank { event.status }),
                        )
                        "pending", "in_progress" -> eventBus.emit(RuntimeEvent.ToolStarted(sessionId, label, detail))
                    }
                }
                is OpenCodeAcpEvent.PermissionRequest -> {
                    sawActivity = true
                    val approvalId = UUID.randomUUID().toString()
                    pendingPermissions[event.requestId] = approvalId
                    approvalToRequestId[approvalId] = event.requestId
                    eventBus.emit(
                        RuntimeEvent.ToolRequested(
                            sessionId = sessionId,
                            request = ToolRequest(
                                approvalId = approvalId,
                                sessionId = sessionId,
                                toolName = event.toolName,
                                explanation = event.detail,
                                affectedPaths = event.affectedPaths,
                                commandPreview = event.commandPreview,
                                risk = event.risk,
                            ),
                        ),
                    )
                }
                is OpenCodeAcpEvent.Failed -> {
                    failure = event.message
                    if (promptRequestId != null) completed = true
                }
                is OpenCodeAcpEvent.Result -> {
                    // The session/prompt response is the ACP turn boundary: once it resolves the
                    // agent has finished producing its reply (or been stopped).
                    if (promptRequestId != null && event.id == promptRequestId) {
                        if (event.stopReason == "refusal" || event.stopReason == "cancelled") {
                            failure = "OpenCode stopped: ${event.stopReason}"
                        }
                        completed = true
                    }
                }
                is OpenCodeAcpEvent.SessionUpdate -> Unit
                is OpenCodeAcpEvent.UnknownUpdate -> if (loggedUnknownKinds.add(event.kind)) {
                    Log.d("OpenCodeBridge", "Unrecognised ACP sessionUpdate kind: ${event.kind}")
                }
                OpenCodeAcpEvent.Ignored -> Unit
            }
        }

        // ACP handshake: client capabilities first, then the session.
        send(
            method = "initialize",
            id = nextRequestId++,
            params = JSONObject()
                .put("protocolVersion", ACP_PROTOCOL_VERSION)
                .put("clientCapabilities", JSONObject().put("fs", JSONObject().put("readTextFile", true).put("writeTextFile", true))),
        )

        var promptDeadline = 0L
        while (process.isAlive || nativeProcess.outputFile.length() > outputOffset) {
            if (userStopRequested) break
            // A prompt that never returns a stop reason must not wedge the session forever.
            if (promptSent && promptDeadline == 0L) promptDeadline = android.os.SystemClock.elapsedRealtime()
            if (promptSent && completed && android.os.SystemClock.elapsedRealtime() - promptDeadline > PROMPT_SETTLE_MS) break

            val available = nativeProcess.outputFile.length() - outputOffset
            if (available <= 0) {
                delay(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(nativeProcess.outputFile, "r").use { file ->
                file.seek(outputOffset)
                file.read(bytes)
            }
            if (count <= 0) continue
            outputOffset += count
            pendingOutput.append(bytes.decodeToString(0, count))
            var newline = pendingOutput.indexOf("\n")
            while (newline >= 0) {
                val line = pendingOutput.substring(0, newline).trimEnd('\r')
                pendingOutput.delete(0, newline + 1)
                if (line.isNotBlank()) handle(parser.parseLine(line))
                newline = pendingOutput.indexOf("\n")
            }
        }
        pendingOutput.toString().trim().takeIf(String::isNotBlank)?.let {
            handle(parser.parseLine(it))
        }
        // Deny anything still pending so the CLI is not left waiting on a dead pipe.
        pendingPermissions.keys.toList().forEach { id ->
            sendPermissionResult(id, JSONObject().put("outcome", "cancelled"))
            pendingPermissions.remove(id)
        }
        runCatching { out.close() }
        wire = null
        return OpenCodeAcpRunResult(completed = completed, failure = failure)
    }

    private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        blockId: Long,
        startsNewBlock: Boolean,
        isFinal: Boolean,
        force: Boolean = false,
    ) {
        val summary = text.replace(Regex("\\s+"), " ").trim().take(2_000)
        if (summary.isBlank()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (force || now - lastThinkingUpdateAt >= 400) {
            lastThinkingUpdateAt = now
            eventBus.emit(
                RuntimeEvent.ReasoningSummary(
                    sessionId = sessionId,
                    summary = summary,
                    blockId = blockId,
                    startsNewBlock = startsNewBlock,
                    isFinal = isFinal,
                ),
            )
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "your project",
                detail = "OpenCode finished the task.",
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: "your project",
                    detail = reason,
                )
            }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is OpenCodeSessionException -> message
            message.contains("not installed", true) -> message.take(300)
            message.contains("free tier", true) ->
                "OpenCode's free models must be reached by the bundled OpenCode client."
            message.contains("auth", true) || message.contains("api key", true) || message.contains("unauthorized", true) ->
                "OpenCode could not authenticate with that provider."
            message.isBlank() -> "OpenCode could not start."
            else -> message.take(500)
        }
    }

    private fun buildContextPrompt(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
    ): String {
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                    !msg.text.startsWith("Failed to") &&
                    !msg.text.startsWith("Error:") &&
                    !msg.text.contains("API Error")
            }
            .dropLast(1)
            .recentWithinCharacterBudget(MAX_CONVERSATION_HISTORY_CHARACTERS)

        val sb = StringBuilder()
        sb.appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
            sb.appendLine("Keep every file and command inside this project workspace.")
        } else {
            sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
            sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            sb.appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        if (installer.isStackInstalled(DevStack.ANDROID)) {
            sb.appendLine("If this is an Android project, the phone already provides JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3, and an offline Maven repository.")
            sb.appendLine("For newly created Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 so the preinstalled offline toolchain can build immediately.")
        } else {
            sb.appendLine("The optional Android build toolchain is not installed in this PocketDev runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in PocketDev Settings before building.")
        }
        sb.appendLine("</project_workspace>")
        sb.appendLine()
        if (priorMessages.isEmpty()) {
            sb.appendLine(currentPrompt)
            return sb.toString()
        }
        sb.appendLine("<conversation_history>")
        sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        sb.appendLine()
        for (msg in priorMessages) {
            val role = if (msg.fromUser) "User" else "Assistant"
            sb.appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                sb.appendLine("Attached files:")
                msg.attachments.forEach { attachment ->
                    sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("</conversation_history>")
        sb.appendLine()
        sb.appendLine("Now, respond to this new message from the user:")
        sb.appendLine(currentPrompt)
        return sb.toString()
    }

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, activeProjectSlug)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, text),
            )
        }
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun startForegroundRuntime(projectName: String) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName),
        )
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(
                        if (completed) RuntimeExecutionService.ACTION_COMPLETE
                        else RuntimeExecutionService.ACTION_FAILED,
                    )
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }.onFailure { error ->
            Log.w("OpenCodeBridge", "Could not post task result notification", error)
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private class OpenCodeSessionException(message: String) : IllegalStateException(message)

    companion object {
        /** ACP protocol revision this client speaks. */
        const val ACP_PROTOCOL_VERSION = 1
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val MAX_CONVERSATION_HISTORY_CHARACTERS = 160_000
        private const val PROMPT_SETTLE_MS = 2_000L
    }
}

private data class OpenCodeAcpRunResult(val completed: Boolean, val failure: String)
