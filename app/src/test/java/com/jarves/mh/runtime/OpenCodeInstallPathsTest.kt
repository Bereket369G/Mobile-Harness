package com.jarves.mh.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for "OpenCode says it is not installed".
 *
 * The host-side install check used to probe `/usr/local/bin/opencode`, which is
 * a chain of absolute guest symlinks. On Android (outside PRoot) those links
 * dangle, so the check reported "not installed" after a flawless install and the
 * app re-downloaded the 46 MB bundle on every launch. These lock in that the
 * check keys off the concrete payload plus the version marker.
 */
class OpenCodeInstallPathsTest {
    @Test
    fun completeInstallIsRecognised() {
        assertTrue(isOpenCodeInstallComplete(payloadExists = true, versionMarker = "1.18.33"))
    }

    @Test
    fun markerWithoutBinaryIsNotInstalled() {
        // The bundle can unpack but the payload can be missing; the marker alone
        // must not be trusted or the app would skip the install.
        assertFalse(isOpenCodeInstallComplete(payloadExists = false, versionMarker = "1.18.33"))
    }

    @Test
    fun binaryWithoutMarkerIsNotInstalled() {
        assertFalse(isOpenCodeInstallComplete(payloadExists = true, versionMarker = null))
        assertFalse(isOpenCodeInstallComplete(payloadExists = true, versionMarker = ""))
        assertFalse(isOpenCodeInstallComplete(payloadExists = true, versionMarker = "   "))
    }

    @Test
    fun nothingInstalledIsNotInstalled() {
        assertFalse(isOpenCodeInstallComplete(payloadExists = false, versionMarker = null))
    }

    @Test
    fun payloadPathIsInsideTheBundledGuestTree() {
        // Guards the hardcoded path: it must be the .l2s payload inside the
        // bundle's own lib directory, not the dangling bin symlink.
        assertTrue(OPENCODE_PAYLOAD.startsWith("/usr/local/lib/opencode/"))
        assertTrue(OPENCODE_PAYLOAD.endsWith(".l2s.opencode0001.0002"))
        assertFalse(OPENCODE_PAYLOAD == OPENCODE_GUEST_BIN)
    }

    @Test
    fun guestEntryPointRemainsTheOnesCommandsUse() {
        // The bridge and verification run *inside* the guest, where the symlink
        // chain resolves, so they must keep using bin/opencode.
        assertTrue(OPENCODE_GUEST_BIN == "/usr/local/bin/opencode")
    }
}
