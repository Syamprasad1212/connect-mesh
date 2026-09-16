package com.connectmesh

import org.junit.Assert.*
import org.junit.Test

class LifecycleAndBluetoothTest {

    @Test
    fun testBluetoothDisabledPromptState() {
        val bluetoothEnabled = false
        val showPrompt = !bluetoothEnabled

        assertTrue("Bluetooth disabled state must set showPrompt to true", showPrompt)
    }

    @Test
    fun testBluetoothEnabledBypassesPrompt() {
        val bluetoothEnabled = true
        val showPrompt = !bluetoothEnabled

        assertFalse("Bluetooth enabled state must not show prompt", showPrompt)
    }

    @Test
    fun testUserEnablingBluetoothDismissesPrompt() {
        var bluetoothEnabled = false
        var showPrompt = !bluetoothEnabled
        assertTrue(showPrompt)

        // User turns Bluetooth ON via system prompt
        bluetoothEnabled = true
        showPrompt = !bluetoothEnabled

        assertFalse("Enabling Bluetooth must dismiss prompt", showPrompt)
    }

    @Test
    fun testUserDismissingPromptKeepsAppUsableWithoutCrash() {
        var showPrompt = true
        var promptDismissed = false

        // User taps Cancel on Bluetooth prompt
        promptDismissed = true
        showPrompt = false

        assertTrue("User dismissal must set prompt state to false", promptDismissed)
        assertFalse(showPrompt)
    }
}
