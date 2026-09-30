# Adding OpenCode as a first-class agent in PocketDev (Mobile Harness)

Status: **implementation in progress** — see "Implementation status" at the end
for what has actually landed and what still needs a device.
Date: 2026-09-29.

---

## TL;DR

OpenCode should be added as a **fourth `AgentKind`** driven over **ACP
(`opencode acp`, JSON-RPC/nd-JSON over stdio)** — the same process-and-stdio
shape the existing `DshRuntimeBridge` already uses. This reuses the app's
"one agent = one driver + one bundle + one event mapping" pattern, requires no
ports/network tricks, and is a near-clone of the DSH bridge. It is a ~1 new
bridge file plus mechanical wiring through the existing registry.

This is also the **only** way the app can offer OpenCode's **keyless free
models**: Zen's free tier is gated server-side to the genuine OpenCode client
(live-verified below), so the real `opencode` binary must be the caller. That
matches exactly what you already saw working on the PC.

The work decomposes into 6 independent, testable steps (see "Implementation
steps"). Nothing here modifies the existing three agents.

---

## Two important findings that reshape the plan

### Finding 1 — The free Zen tier is gated to the *genuine client*; a header spoof is NOT enough (live-verified)

This corrects an earlier draft of this plan (and an earlier claim in the
companion addendum) that said the app's User-Agent spoof already defeats the
"only works in OpenCode" gate. **It does not.** I tested the live Zen endpoint
directly and the gate is enforced server-side, stronger than a header:

| Request to `https://opencode.ai/zen/v1/...` | Result |
|---|---|
| `GET /v1/models`, no auth, default UA | **200** — full catalog is public |
| `GET /v1/models`, no auth, spoofed `opencode/1.18.33` UA | **200** |
| `POST /v1/chat/completions` (free model `big-pickle`), **no** key, default UA | **403** `{"type":"FreeTierError","message":"...can only be used from within OpenCode"}` |
| `POST .../chat/completions`, **no** key, spoofed `opencode/1.18.33` + `x-session-id` + `anthropic-version` | **403** `FreeTierError` (identical) |
| `POST .../chat/completions`, any Bearer key | **401** `{"type":"AuthError","message":"Invalid API key."}` (checked before/independent of the free-tier gate) |

Read together, the three facts are the whole story:

1. **The catalog is open; the inference is gated.** Model discovery needs no key
   (which is why `AgentScreen.discoverModels` treats `OPENCODE_ZEN` as
   `supportsPublicDiscovery`, AgentScreen.kt:248-249). But actually *calling* a
   free model without a key is refused unless the caller is the real client.
2. **It is not a UA/header fingerprint.** Spoofing `User-Agent: opencode/…` +
   `x-session-id` (what the app does today at `ProviderApiClient.kt:156-160`)
   still gets `403`. So the existing spoof **does not** give DeepSeek Harness
   the free models — it presumably only satisfies a weaker identity check on
   *paid* Zen routes. The keyless free path is reachable **only from inside the
   genuine `opencode` binary** (which presents whatever internal credential the
   server recognises). That is exactly what you experienced on the PC: install
   OpenCode, run it, free models work with no account and no key.
3. **A valid Zen API key is the other way in.** An invalid key gets `401`; a
   valid one authenticates the request and reaches the free models (which cost
   $0.00). So "free" = zero cost per request, but you need *either* the real
   client (keyless) *or* a key — there is no third, anonymous HTTP path.

**Implication — this is exactly why we must bundle the real `opencode` binary.**
Adding OpenCode as a first-class agent is not optional polish; it is the *only*
way the app can offer the keyless free tier, because the genuine client must be
the caller. PocketDev must not try to call Zen's free models itself. So:

- The real `opencode` (inside the guest) talks to Zen, and the app just drives
  it over ACP. The free models then work with no key, no account — matching the
  PC experience.
- We keep `ProviderKind.OPENCODE_ZEN` for **discovery** (public catalog) and for
  users who *choose* to paste a Zen key into `ApiKeyVault` (then the app's
  existing OpenAI-responses path can call free *and* paid models).
- The app's existing UA spoof (L156-160) is best left as-is for the paid path,
  but it should **not** be advertised as the way to the free models — that claim
  is wrong. (Its pinned `1.18.20` vs upstream `1.18.33` is cosmetic either way,
  since the free tier ignores the header entirely.)

### Finding 2 — There is a cleaner integration surface than `serve`: **ACP**

OpenCode exposes three headless surfaces. The best fit here is **not** the
obvious one:

| Surface | Shape | Verdict for PocketDev |
|---|---|---|
| `opencode acp` | **JSON-RPC / nd-JSON over stdio** subprocess | **Best.** Same shape as the existing `DshRuntimeBridge` (`--profile sdk` -> nd-JSON on stdio -> map to `RuntimeEvent`). No port, no network namespace, no auth, process lifecycle already solved. |
| `opencode serve` | Long-lived HTTP server + SSE `/event` | Viable fallback, but adds a port + long-lived-server lifecycle + auth that the app doesn't need for a stdio driver. |
| `opencode run --format json` | One-shot, raw JSON events | Simplest possible first slice (no bidirectional tool-approval), but no `INTERACTIVE_APPROVALS` and no resume. Good for a proof-of-concept, not the final agent. |

`opencode acp` is configured by editors literally as
`{ "command": "opencode", "args": ["acp"] }` (Zed, JetBrains, avante.nvim,
CodeCompanion examples in the ACP docs). All features work over ACP: built-in
tools, custom tools/slash commands, MCP, AGENTS.md rules, agents + permissions.
(Only `/undo` and `/redo` slash commands are unsupported — note: PocketDev's
own undo/accept is checkpoint-based, so this is a non-issue.)

This turns the work from "invent a protocol integration" into "port the DSH
bridge's stdio event-mapping to ACP method names." That is the whole trick, and
it's why this is a low-risk, high-confidence change.

---

## How the developer made the existing harnesses "work fully" (answering your question)

He did **not** clone/re-implement any harness. The pattern is uniform across all
three:

1. **Package a pinned official binary/npm payload** as a runtime bundle
   (`scripts/runtime-bundles/`), checksum-verified, exported *without* any
   credentials/sessions/projects.
2. **Add one enum entry** `AgentKind` (Models.kt) carrying `stableId/title/
   subtitle/downloadNote`. The compiler then flags every `when(AgentKind)`
   that must be extended.
3. **Extend the installer's exhaustive `when`s** in `RuntimeInstaller.kt`
   (`ensureAgentInstalled` ~L225, `isAgentInstalled` ~L238, `ensure*Installed`
   L212-214/L231-233, version-check + update L328-378) with a `ensureXInstalled`
   that unpacks the bundle.
4. **Write one `RuntimeBridge` that drives the agent's headless protocol** and
   maps its stream into the app's `RuntimeEvent` sealed interface (Models.kt:
   274-307): `SessionStarted, AssistantDelta, ReasoningProgress,
   ReasoningSummary, ToolStarted, ToolRequested/ToolApproved/ToolRejected,
   ToolCompleted, FilesChanged, PreviewStarted, SessionCompleted, SessionFailed`.
5. **Register it** in `AgentRegistry.builtIns(...)` (AgentDriver.kt:44-85) with
   a capability set. The `init` requires every `AgentKind` to be registered
   (AgentDriver.kt:37), so a missing registration is a startup crash, not a
   silent gap.
6. **Expose providers** via a `..._PROVIDERS` set so shared UI knows which
   providers are valid for that agent without knowing the CLI.

The `AgentDriver`/`AgentRegistry` seam is explicitly designed for this: *"Adding
an agent does not change existing bridges."* So OpenCode slots in as a new
driver + new bridge and leaves Claude/DSH/Antigravity untouched.

Concretely, DSH is the closest template:
- Bridge: `runtime/DshRuntimeBridge.kt` (launches `/usr/local/bin/dsh --profile
  sdk`, parses nd-JSON, maps to `RuntimeEvent`, handles approvals, diffs,
  checkpoints, preview).
- Bundle: `scripts/runtime-bundles/build-dsh-from-installed-android.sh` (npm
  payload + launcher symlink + version marker -> deterministic
  `pocketdev-dsh-arm64-<ver>.tar.zst`, SHA-256 in `dist/runtime-bundles/manifest.json`).
- Test: `app/src/test/java/com/jarves/mh/runtime/DshBridgeTest.kt`.

---

## Architecture facts that make this safe (verified in-source)

- **Shared network namespace (not needed for ACP, but good to know):** the app
  already runs a local HTTP server on the Android host
  (`LocalFormatGateway.kt:23-24`, `ServerSocket` on 127.0.0.1) and passes its
  URL into the guest as `ANTHROPIC_BASE_URL` (RuntimeBridge.kt:72). That the
  guest can dial the host over 127.0.0.1 proves the guest shares the host netns.
  ACP via stdio sidesteps this entirely, which is another reason to prefer it.
- **Process lifecycle is solved:** `NativeSpawnProcess` + `pocket_spawn.c`
  already spawn the guest command with stdio capture and a per-launch process
  group (killing the wrapper kills the subtree). ACP is a stdio child, so this
  applies unchanged.
- **PTY terminal exists separately** (`pocket_spawn.c` posix_openpt/setsid/
  TIOCSCTTY) for `TerminalScreen` — so `opencode` TUI is also available in the
  in-app terminal if desired (a nice bonus, zero code).

---

## Distribution / packaging (verified)

`opencode-ai` on npm is a thin wrapper (7.8 KB, 4 files) that installs the real
platform binary via `optionalDependencies`; the ARM64 Linux one is
`opencode-linux-arm64`. Current version **1.18.33**. This mirrors DSH exactly:

Bundle build = `npm install opencode-ai@<pin> opencode-linux-arm64@<pin>` in
the guest, export `node_modules` + `opencode` launcher symlink + a
`.pocket-opencode-version` marker, deterministic tar + zstd -> publish + SHA-256
in `manifest.json`. Add a `build-opencode-from-installed-android.sh` cloned
from `build-dsh-from-installed-android.sh`.

(Prerequisite: the guest has Node/npm from the `core` bundle, same as DSH — no
new runtime dependency.)

---

## Implementation steps (independent, testable; ordered)

### Step 1 — Bundle (no Kotlin yet)
- New `scripts/runtime-bundles/build-opencode-from-installed-android.sh`
  (clone of the DSH script; swap the payload to `usr/local/lib/opencode`
  node_modules, `usr/local/bin/opencode` symlink, `.pocket-opencode-version`).
- Build on an ARM64 host/device, produce
  `pocketdev-opencode-arm64-<ver>.tar.zst`, add SHA-256 to
  `dist/runtime-bundles/manifest.json`.
- Document in `scripts/runtime-bundles/README.md` (add an `opencode` bullet).

### Step 2 — Model + install plumbing (mechanical, compiler-guided)
- `Models.kt`: add `AgentKind.OPENCODE("opencode", "OpenCode", "The open-source
  coding agent · all providers incl. Zen", "<size>")`. (Note the enum's
  `downloadNote` is a display string — set it to the real compressed size once
  the bundle is built.)
- Add `OPENCODE_PROVIDERS` set (start = all of the key-based providers; Zen
  included) and, if you want parity, an `OPENCODE_PROTOCOL_PROVIDERS`-style set.
- `RuntimeInstaller.kt`: add an `ensureOpencodeInstalled(...)` mirroring
  `ensureDshInstalled`, and extend the `when`s at L212-214, L231-233, L238-250,
  and the version-check/update blocks (L328-378) with the new arm.
- The compiler will point at every spot; that is the intended workflow.

### Step 2.5 — Rich composer (`/` + `@` autocomplete) — shared, agent-agnostic
- See `OPENCODE_FREEMODELS_AND_COMPOSER.md` (Q2) for the full design.
- Migrate the chat composer from a plain `String` to `TextFieldValue` (mirror
  the existing pattern in `ui/TerminalScreen.kt`) so the cursor/selection is
  known; add an autocomplete popup for `/` (slash commands) and `@` (file
  mentions) driven off the cursor offset.
- Add `SLASH_COMMANDS` / `FILE_MENTIONS` to `AgentCapability` and gate the popup
  per agent so it degrades gracefully for Claude/DSH/Antigravity. This is the
  most reusable + user-visible win in the effort and is independently shippable.

### Step 3 — The bridge (the real work)
- New `runtime/OpenCodeRuntimeBridge.kt`, modeled on `DshRuntimeBridge.kt`.
- Launch: `/usr/local/bin/opencode acp` (stdin/stdout nd-JSON). Working dir =
  project workspace (same `-w /workspace` + `-b` binding the installer already
  uses).
- Speak ACP JSON-RPC. Map ACP session/prompt/update notifications to
  `RuntimeEvent`:
  - assistant text -> `AssistantDelta`
  - reasoning/thinking -> `ReasoningProgress` / `ReasoningSummary`
  - tool call start -> `ToolStarted`; tool permission request -> `ToolRequested`
    (+ `ToolApproved`/`ToolRejected` on response) to reuse the existing
    interactive-approval UI
  - file mutations -> `FilesChanged` (reuse the existing diff/ChangeItem path)
  - terminal/web start -> `PreviewStarted`; finish -> `SessionCompleted`;
    errors -> `SessionFailed`
- Respect the app's bounded-memory discipline (see `runtime/BoundedFileReads.kt`
  and `RuntimeBridge.kt: recentWithinCharacterBudget`): cap retained history /
  collected output like the other bridges do.
- Implement undo/accept via the existing `WorkspaceCheckpoints` mechanism (don't
  lean on ACP's `/undo`, which is unsupported).

### Step 4 — Register + capabilities
- `AgentDriver.kt`: add a `BuiltInAgentDriver(AgentKind.OPENCODE, opencode,
  setOf(API_KEY, PROVIDER_PICKER, MODEL_PICKER, REASONING_EFFORT, RESUME,
  INTERACTIVE_APPROVALS))` and add a param to `AgentRegistry.builtIns(...)`.
  (Capability list chosen to match what ACP actually supports; trim if reality
  differs.)
- `MainViewModel`: construct the new bridge and pass it to
  `AgentRegistry.builtIns`.

### Step 5 — UI/provider gating
- `ui/AgentScreen.kt` / `SettingsScreenModern.kt`: the agent picker is driven by
  `AgentKind` + capability sets, so it should pick up the new agent with little
  or no change. Gate provider choices with `OPENCODE_PROVIDERS`; ensure the model
  picker/reasoning-effort controls bind to what ACP exposes.
- Provider launch env: add an ACP/OpenCode flavor to the launch-config builder
  (OpenCode reads its own config/`auth.json` and env, not `ANTHROPIC_*`; the
  simplest correct path is to pass the key via env/`auth.json` and select model
  with `--model`/config, mirroring how the DSH/Claude bridges inject secrets).

### Step 6 — Tests
- New `app/src/test/java/com/jarves/mh/runtime/OpenCodeBridgeTest.kt`, modeled
  on `DshBridgeTest.kt` (feed canned ACP nd-JSON lines -> assert the
  `RuntimeEvent` sequence). Reuse the existing JSON test helpers.
- Add the new agent to `RuntimeCompatibilityTest` / provider-error-detection
  coverage if those enumerate agents.

### Step 7 — Release plumbing
- `manifest.json` (built in Step 1), online-vs-offline `BuildConfig` bundling,
  and the update path in `AppUpdater` follow the existing per-agent pattern.

---

## Risks / gotchas to respect

- **ACP is a versioned upstream protocol.** Pin the OpenCode version in the
  bundle; when you bump it, re-test the event mapping. ACP method names /
  notification shapes can change across releases.
- **Provider auth model differs.** Claude/DSH inject `ANTHROPIC_*` env; OpenCode
  uses its own provider config + `~/.local/share/opencode/auth.json` + env
  (`OPENCODE_API_KEY`, provider-specific). Seeding a key from the app's
  `ApiKeyVault` into that store is a real task — decide the mechanism (write
  auth.json into the guest vs env) and keep secrets out of the exported bundle
  (the bundle scripts already exclude credentials).
- **PRoot is not a security boundary** (README says so). Same posture as the
  existing agents; nothing new.
- **The free Zen tier only works via the real client** (Finding 1). Do not
  attempt to make the app itself call Zen's free models — the gate rejects it.
  Route free-model usage through the bundled `opencode` binary (ACP), and treat
  the app-side `ProviderApiClient` Zen spoof as the *paid/discovery* path only.
  If Zen's exact gating ever changes, re-verify with the live-probe table in
  Finding 1 rather than trusting the UA spoof.
- **Redistribution terms.** The bundle ships a pinned official OpenCode binary
  (MIT-licensed `opencode-ai` wrapper + upstream binary), same posture as the
  Claude/DSH/Antigravity bundles. The bundle README even calls out
  "Review Google's redistribution terms" for Antigravity — do the equivalent
  review for the OpenCode binary before shipping an APK.

---

## The critical unknown / first spike

The whole plan hinges on: **does the pinned `opencode-linux-arm64` binary run
under the app's proot+Ubuntu guest, and does `opencode acp` emit the ACP
notifications we map?** That is not answerable without a device (ARM64) with
the runtime installed.

**This spike is now packaged and ready to run.** Rather than hand-driving the
handshake, there is a tested tool that does it for you and prints exactly the
facts the bridge depends on:

- `scripts/runtime-bundles/opencode-acp-spike.js` — drives `opencode acp` through
  the same `initialize` → `session/new` → `session/prompt` handshake the bridge
  uses, handles permission + `fs/*` requests, and writes a raw nd-JSON
  transcript plus a summary of every `session/update` kind, tool call, and stop
  reason. Smoke-tested end-to-end against a mock ACP server in this workspace.
- `scripts/runtime-bundles/run-opencode-acp-spike.sh` — installs the pinned
  OpenCode in the guest and runs the driver (one paste in the app terminal).
- `docs/OPENCODE_ACP_SPIKE.md` — the device runbook: setup, exact commands, what
  to paste back, and troubleshooting.

### First on-device run: partial success + a driver bug (now fixed)

The first real guest run installed `opencode@1.18.33` and completed
`initialize` and `session/new` — then **crashed in the driver itself** (not in
OpenCode) before it could send `session/prompt`, so the transcript had no
`session/update` events. Cause: the driver used
`JSON.stringify(x).slice(0, n)`, and `JSON.stringify(undefined)` returns
`undefined`, not a string. Real `opencode` returns no `availableCommands` and
no `modes` on `session/new` (it returns `configOptions` instead), so that
expression threw. Fixed in commit `28fa450` via a `brief()` helper, and
re-verified against a mock that mimics the real client shape.

**Facts the first run already confirmed (ground truth):**

- `protocolVersion: 1`; capabilities include `loadSession`, `mcp http/sse`,
  `embeddedContext`+`image` prompts, and session `close/fork/list/resume`.
- Auth is offered as `opencode-login` ("Run `opencode auth login` in the
  terminal") — but is **not required**.
- **The keyless free-tier design works.** `session/new` returns a `model`
  `configOptions` selector whose `currentValue` is `opencode/big-pickle` and
  whose options are all free Zen models: `big-pickle`, `space-bunny-free`,
  `ling-3.0-flash-fin-free`, `longcat-2.5-preview-free`, `mimo-v2.6-flash-free`,
  `muse-spark-1.3-contributor-free`, `nemotron-3-ultra-free`,
  `nemotron-3.5-lightning-free` — **no login, no API key.**
- A `mode` selector offers `build` and `plan`.

**Design consequence:** the slash-command / model metadata does **not** come
from an `availableCommands` field as the spec suggested; the real client models
it as typed `configOptions` selectors. The bridge's dynamic-`/` plumbing should
therefore key off `configOptions` (model + mode), and the static
`DEFAULT_SLASH_COMMANDS` should be driven by the model selector's option list
where it maps, with `build`/`plan` surfaced as real modes. A second run to
capture the full `session/update` stream (after the fix) is the remaining
unknown; the first run validated the handshake, the model catalog, and the
keyless premise.

Concrete steps to finish the spike:

1. The runtime is installed and `opencode@1.18.33` is already in the guest.
2. Re-run `bash /workspace/nimble-hopper/scripts/runtime-bundles/run-opencode-acp-spike.sh`
   in the app terminal (the fixed driver now completes the full handshake).
3. Paste the summary (and ideally the transcript) back.

That captured transcript is the ground truth for the event mapping and de-risks
Steps 3-6 before any of them are written. (This is also the same manual loop the
original author must have used to get DSH/Claude/Antigravity working.)

---

## Note on tooling in this workspace

The Android build toolchain (Gradle 8.14.3 / JDK 17 / SDK 36) **is** available
here, so the Kotlin sources are compiled and the full unit suite is run on every
change. Two environmental gaps remain and are worked around explicitly:

- **No NDK / CMake**, and the native submodules (`proot`, `libandroid-shmem`)
  are empty — so a full APK assembly cannot be produced here. The opt-in
  `mhNativeBuild=false` toggle exists for exactly this: it lets the Kotlin
  sources compile and the unit tests run without the native toolchain, while
  default behaviour (native build on) is unchanged.
- **The bundle still needs a real ARM64 host with the runtime installed** to
  produce the checksum-pinned `.tar.zst`.

---

## Implementation status (2026-09-29)

### Landed and verified in-tree

Full unit suite is green: **95 tests, 0 failures, 0 errors**. No existing
agent test changed behaviour (Antigravity 11, DSH 16, launch-config 7 all
still pass), which upholds the "adding an agent does not change existing
bridges" rule.

| Step | Status | Notes |
|---|---|---|
| Model plumbing | done | `AgentKind.OPENCODE` + `OPENCODE_PROVIDERS`; fresh profiles default to `OPENCODE_ZEN` so the keyless path is the default. |
| Registry / wiring | done | `AgentRegistry.builtIns` takes an `openCode` bridge; `MainViewModel` constructs it. Capabilities deliberately omit `API_KEY` so no key is ever demanded. |
| ACP protocol | done | `OpenCodeAcpProtocol.kt` — nd-JSON JSON-RPC parser → `OpenCodeAcpEvent`. |
| ACP bridge | done | `OpenCodeRuntimeBridge.kt` — initialize → session/new → session/prompt, permission round-trip, checkpoints, bounded memory. |
| Installer | done | Bundle const, marker, install/update (`npm install opencode-ai@<v>`), `isAgentInstalled`, version reporting, npm-registry update check. |
| UI `when`s | done | Download size, color, initials, display name. |
| Bundle script | done | `scripts/runtime-bundles/build-opencode-from-installed-android.sh` + manifest + README entry. |
| Free-model detection | done | Zen catalog marks free by trailing `-free` (no pricing block); anchored-suffix rule added, 11 free models verified against the live catalog. |
| ACP spike tooling | done | `opencode-acp-spike.js` + `run-opencode-acp-spike.sh` + `docs/OPENCODE_ACP_SPIKE.md`; driver smoke-tested against a mock ACP server **and** run once in a real guest. Handshake, capability set, and the keyless free-model catalog are confirmed; a driver crash on the absent `availableCommands` field was found and fixed (`28fa450`). |
| Bridge robustness | done | Unknown `session/update` kinds are surfaced (logged once) instead of dropped; any update carrying a `toolCallId` is treated as a tool call so approvals are never missed. |
| Composer `TextFieldValue` migration | done | Chat composer converted from bare `String` to `TextFieldValue` (with `TextFieldValue.Saver`) to carry a caret for the `/` + `@` popup. |
| Composer `/` + `@` popup | done | `ComposerAutocomplete.kt` (pure, Compose-free caret-anchored trigger detection) + `ComposerAutocompleteUi.kt` (candidate building, filtering, popup composable). Gated behind two new capabilities so only OpenCode ever sees it. Wired into `ChatTab`. 17 tests. |

### Two real bugs found and fixed along the way

1. **Build could not configure at all.** `android { kotlinOptions.jvmTarget = "17" }`
   is invalid under Kotlin 2.2.21 + AGP 8.13.2. The genuine compile error was
   masked by a crashing Kotlin error-renderer (`ExceptionInInitializerError` on
   `DefaultErrorMessages`), so the build only reported an opaque class-init
   failure. Replaced with the modern top-level
   `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }`.
2. **`rawInput` preview.** Real ACP sends `rawInput` as a JSON *object*;
   `optString` on it returns `""`, so the approval card would have shown no
   command at all. `OpenCodeAcpProtocol` now normalises both object and string
   forms. Locked in by a test.

Also added an opt-in `mhNativeBuild` toggle (default `true`, behaviour
unchanged) so Kotlin-only verification can run on a machine without the NDK.

### Still needs a real device (cannot be done here)

- **Bundle sha256 is empty on purpose.** `ensureOpenCodeInstalled` refuses to
  install until it is pinned, with an actionable message. Producing the real
  `.tar.zst` needs an ARM64 host with the runtime installed.
- **ACP wire-truth is now partly confirmed.** The first real guest run verified
  `protocolVersion: 1`, the capability set, the `session/new` response shape, and
  the keyless free-model catalog. It stopped short of the `session/update` stream
  because of the driver bug described above, so the exact notification kinds and
  tool-call shapes OpenCode emits are still spec-derived rather than captured.
  One more run of the fixed driver closes this — it is still the single
  de-risking step for the event mapping.
- **Slash-command list is currently a static default.** `DEFAULT_SLASH_COMMANDS`
  in `ComposerAutocompleteUi.kt` mirrors the commands OpenCode documents (minus
  `/undo` and `/redo`, which ACP does not support). The device run disproved the
  assumption that ACP advertises them on `session/new`: the real client returns
  **no** `availableCommands` and instead models model and mode as typed
  `configOptions` selectors. The static list should therefore be driven from that
  selector data (surfacing `build`/`plan` as real modes) rather than a guessed
  command list. The capability-gated plumbing already carries it.
- **Online model list is derived from a live catalog fetch, not the guest.** The
  free-model *labelling* is correct (verified against the live Zen catalog), but
  an actual `$0` completion still requires the bundled binary to be the caller.

### What was deliberately not faked

`OPENCODE_BUNDLE.sha256` is empty and the installer refuses to install until it
is pinned. A bundle that fails checksum on a user's phone would be worse than an
actionable error, so the guard stays until a real `.tar.zst` is built and hashed
on an ARM64 host.

---

## Toolchain findings (verified on the aarch64 device, 2026-09-29)

An attempt was made to produce a full installable APK on-device. Three of the four
blockers were real and fixable; the fourth is a hard architecture wall.

### Fixed on-device

| Blocker | Resolution |
|---|---|
| `third_party/proot` and `third_party/libandroid-shmem` empty (no `.git/modules`) | Cloned from `termux/*` and checked out the exact pinned commits `61681c6` and `7f0bd7e`. All 72 source files referenced by the CMake build verified present. |
| SDK had no CMake | Google's `cmake-3.22.1-linux.zip` turned out to be an **x86_64** build (cannot run on ARM). Installed a native **aarch64** CMake via the `cmake` PyPI wheel (4.4.3) into `$SDK/cmake/3.22.1`, plus a native `ninja` (1.13.2) from the `ninja` wheel. |
| NDK `26.1.10909125` was an empty `.installer` stub | Downloaded `android-ndk-r26d-linux.zip` (r26d) and installed it to the exact expected revision. |

### The hard wall: the NDK has no aarch64 host build

The Android NDK ships host tools **only for x86_64**. The installed
`clang` is an `x86-64` ELF (`e_machine=62`); this device is `aarch64`
(`e_machine=183`). Running it fails with `not found` from `/bin/sh` — the
architecture mismatch, not a missing file. A minimal CMake
`try_compile` against the NDK toolchain reproduces it exactly:

```
/root/android-sdk/ndk/.../prebuilt/linux-x86_64/bin/clang ... -o testCCompiler.c.o
/bin/sh: 1: .../bin/clang: not found
ninja: build stopped: subcommand failed.
```

There is no supported way to cross-compile this app's native layer (proot,
prootloader, `pocketspawn`) from an ARM host. The repo also ships **no
prebuilt `.so`**, so the native objects genuinely must be compiled.

**Conclusion:** a runnable APK requires an x86_64 machine (or CI) with the
Android SDK 36 + NDK `26.1.10909125`. On such a machine the app builds as-is —
no source changes are needed for the toolchain; only the OpenCode bundle
sha256 remains to be pinned. On-device, the meaningful and fully-green gate is
the Kotlin build + unit tests with `-PmhNativeBuild=false`, which is the toggle
this work added.

---

## On-device verification and the four defects it exposed

A side-by-side build (`io.github.bereket369g.pocketdev`, label
`PocketDev OpenCode`) was produced through CI and installed on the device
alongside the original app. Running the OpenCode agent on a real device
surfaced four defects that no host-side test could have caught, because all
four only manifest inside PRoot or on real hardware. All are fixed and
regression-tested (**105 tests, 0 failures**).

### 1. Install detection probed a symlink chain that dangles outside PRoot

The guest entry point `/usr/local/bin/opencode` is a chain of *absolute* guest
symlinks terminating in the real ARM64 payload:

```
bin/opencode
  -> /usr/local/lib/opencode/node_modules/.bin/opencode
  -> ../opencode-ai/bin/opencode.exe
  -> /usr/local/lib/opencode/node_modules/opencode-linux-arm64/bin/.l2s.opencode0001
  -> /usr/local/lib/opencode/node_modules/opencode-linux-arm64/bin/.l2s.opencode0001.0002
```

Host-side, those absolute links do not resolve, so `File.canExecute()` on the
entry point reported "not installed" **after a flawless install**. The app
therefore re-downloaded the bundle indefinitely and then announced that
OpenCode was not installed. Host-side checks now probe the concrete payload
(`OPENCODE_PAYLOAD`), exactly as the DeepSeek Harness check already did, and
the decision is a pure tested helper in `OpenCodeInstallPaths.kt`. The
in-guest `verifyGuest` call sites still use the entry point, which is correct
because inside PRoot the chain resolves.

### 2. Bundle downloads were staged in a directory Android may purge

Staging went to `context.cacheDir`, which the platform is free to purge under
memory pressure. A 46 MB bundle staged there can disappear mid-transfer, and it
surfaced as `ENOENT` on the `.part` file *after the progress bar reported
completion* — a misleading error that looked like a missing file rather than a
reclaim. Three changes:

- staging moved to `filesDir`, which is not auto-purged;
- a `Mutex` serialises downloads so two concurrent installs of the same bundle
  cannot unlink each other's shared `.part` file;
- a rejected `Range` or a stage file that vanished after writing now restarts
  cleanly instead of surfacing a raw `ENOENT`, bounded by
  `MAX_DOWNLOAD_RETRIES` so the self-healing path cannot recurse without limit.

### 3. Onboarding demanded an API key from a provider that needs none

OpenCode Zen serves a free tier with no account, but both the setup screen and
the connection test were hard-wired around "hasKey". The only route past
onboarding was to supply a key belonging to some *other* provider, which is
exactly the dead end that was reported. `ProviderKind` now carries
`worksWithoutApiKey`; the setup screen presents the key as optional, enables
discover/validate/save without one, and `ProviderApiClient.validate` accepts an
`allowWithoutApiKey` flag instead of short-circuiting on a blank key. The
client already omits the `Authorization` header when the key is blank, so the
anonymous request is well-formed.

### 4. Model discovery special-cased a provider by name

`AgentScreen` compared the selected provider against `OPENCODE_ZEN` to decide
whether discovery may run without a key. It now reads `worksWithoutApiKey`, so
any future keyless provider follows the same rule rather than needing another
identity check.

### The re-entrant setup loop

After a failed setup the app legitimately returns to the setup screen on next
launch, because onboarding was never saved. That was a *symptom* of defect 2,
not a separate bug: the download threw before install completed, so nothing was
ever marked ready. With staging on non-evictable storage the download completes
and onboarding is saved normally.
