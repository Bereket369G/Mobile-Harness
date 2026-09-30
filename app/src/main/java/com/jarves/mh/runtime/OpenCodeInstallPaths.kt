package com.jarves.mh.runtime

/**
 * Guest paths used to decide whether the OpenCode agent is installed.
 *
 * The guest entry point `/usr/local/bin/opencode` is a chain of *absolute* guest
 * symlinks:
 *
 *   bin/opencode
 *     -> /usr/local/lib/opencode/node_modules/.bin/opencode
 *     -> ../opencode-ai/bin/opencode.exe
 *     -> /usr/local/lib/opencode/node_modules/opencode-linux-arm64/bin/.l2s.opencode0001
 *     -> /usr/local/lib/opencode/node_modules/opencode-linux-arm64/bin/.l2s.opencode0001.0002
 *
 * On the Android host (outside PRoot) those absolute links dangle, so probing the
 * entry point with [java.io.File.exists] / [java.io.File.canExecute] reports
 * "not installed" even after a flawless install. Host-side checks must therefore
 * probe the concrete payload, exactly as the DeepSeek Harness check does.
 */
internal const val OPENCODE_GUEST_BIN = "/usr/local/bin/opencode"

internal const val OPENCODE_PAYLOAD =
    "/usr/local/lib/opencode/node_modules/opencode-linux-arm64/bin/.l2s.opencode0001.0002"

/**
 * Whether the host-visible runtime root contains a usable OpenCode install.
 *
 * [payloadExists] must be the result of a direct, non-following check of
 * [OPENCODE_PAYLOAD]; [versionMarker] is the trimmed contents of the
 * `.pocket-opencode-version` marker, or null/blank when the install never
 * completed. Both must hold: a marker without a binary, or a binary without a
 * marker, is a partial install and must be redone.
 */
internal fun isOpenCodeInstallComplete(payloadExists: Boolean, versionMarker: String?): Boolean =
    payloadExists && !versionMarker.isNullOrBlank()
