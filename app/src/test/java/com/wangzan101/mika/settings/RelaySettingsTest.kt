package com.wangzan101.mika.settings

import com.wangzan101.mika.core.remote.RelayPairing
import com.wangzan101.mika.mcp.McpModes
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class RelaySettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun settings() = RelaySettings(tmp.root, AesGcmKeyCipher { key })
    private fun pairing() = RelayPairing("https://relay.example/", "d".repeat(43))

    @Test fun pairingDoesNotEnableAccessAndCredentialsAreEncrypted() {
        val s = settings(); assertFalse(s.config.value.paired); s.save(pairing())
        assertTrue(s.config.value.paired); assertFalse(s.config.value.enabled)
        val raw = tmp.root.resolve("remote-relay.bin").readText(Charsets.ISO_8859_1)
        assertFalse(raw.contains(pairing().deviceToken))
        s.setEnabled(true)
        val restored = settings(); assertTrue(restored.config.value.enabled)
        assertEquals(pairing().deviceToken, restored.pairing()!!.deviceToken)
    }

    @Test fun deviceAccessOverTheGatewayIsOffByDefaultPersistsAndResetsOnRepair() {
        val s = settings(); s.save(pairing()); s.setEnabled(true)
        assertFalse(s.config.value.deviceAccess)
        s.setDeviceAccess(true)
        assertTrue(settings().config.value.deviceAccess)
        s.setEnabled(false); s.setEnabled(true)
        assertTrue(settings().config.value.deviceAccess)
        s.clear(); s.save(pairing())
        assertFalse(settings().config.value.deviceAccess)
        try { RelaySettings(tmp.newFolder(), AesGcmKeyCipher { key }).setDeviceAccess(true); fail() } catch (_: IllegalStateException) { }
    }

    @Test fun corruptFileAndLostKeyFailClosed() {
        settings().save(pairing())
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val unreadable = RelaySettings(tmp.root, AesGcmKeyCipher { other })
        assertFalse(unreadable.config.value.enabled); assertNull(unreadable.pairing())
        tmp.root.resolve("remote-relay.bin").writeText("broken")
        assertFalse(settings().config.value.paired)
    }

    @Test fun disablePersistsAndClearRemovesCredentials() {
        val s = settings(); s.save(pairing()); s.setEnabled(true); s.setEnabled(false)
        assertFalse(settings().config.value.enabled); assertNotNull(settings().pairing())
        s.clear(); assertFalse(settings().config.value.paired); assertNull(settings().pairing())
        assertFalse(tmp.root.resolve("remote-relay.bin").exists())
    }

    @Test fun cannotEnableUnpairedOrSaveInsecureGateway() {
        try { settings().setEnabled(true); fail() } catch (_: IllegalStateException) { }
        try { settings().save(RelayPairing("http://relay.example/", "d".repeat(43))); fail() } catch (_: IllegalArgumentException) { }
        assertFalse(settings().config.value.enabled)
    }

    @Test fun notificationOffClosesAllTransportsAndUsbActionsDoNotEnableRemote() {
        val relay = settings(); relay.save(pairing())
        val network = McpSettings(tmp.root, AesGcmKeyCipher { key })
        val usb = McpUsbSettings(tmp.root, { "test-boot" }, { tmp.root.resolve("usb-info.json") })
        McpModes.apply(McpModes.ACTION_USB_ON, network, usb, relay)
        assertFalse(relay.config.value.enabled)
        relay.setEnabled(true); network.setEnabled(true)
        McpModes.apply(McpModes.ACTION_TURN_OFF, network, usb, relay)
        assertFalse(relay.config.value.enabled); assertFalse(network.enabled.value); assertFalse(usb.enabled.value)
    }
}
