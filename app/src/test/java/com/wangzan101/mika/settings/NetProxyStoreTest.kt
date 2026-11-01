package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 设置 › 网络代理:可能带账号密码,加密存成文件、不落明文;地址先核对再存;给人看的写法不带账号密码;旧版只给容器用时存的那份照样接上。 */
class NetProxyStoreTest {
    private val dir: File = Files.createTempDirectory("proxy").toFile()

    /** 测试用的「加密」:逐字节取反,够看出文件里不是原文。 */
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { plain[it].toInt().inv().toByte() }
        override fun decrypt(data: ByteArray) = encrypt(data)
    }

    @Test fun storedEncryptedAndReadBack() {
        val proxy = "http://alice:s3cret@10.0.0.2:8080"
        NetProxyStore(dir, cipher).set(proxy)
        val file = File(dir, "net-proxy.bin")
        assertTrue(file.isFile)
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("文件里不是明文", raw.contains("s3cret") || raw.contains("alice"))
        // 新开一份(像重启之后)照样读得出来
        assertEquals(proxy, NetProxyStore(dir, cipher).get())
    }

    @Test fun emptyRemovesTheFile() {
        val store = NetProxyStore(dir, cipher)
        store.set("socks5://127.0.0.1:1080")
        store.set("  ")
        assertEquals("", store.get())
        assertFalse(File(dir, "net-proxy.bin").exists())
        assertEquals("", NetProxyStore(dir, cipher).get())
    }

    /** 解不开(密钥没了)当没设,不让命令带着乱码去连。 */
    @Test fun unreadableCountsAsNotSet() {
        File(dir, "net-proxy.bin").writeBytes(byteArrayOf(1, 2, 3))
        val broken = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(data: ByteArray): ByteArray = throw IllegalStateException("key gone")
        }
        assertEquals("", NetProxyStore(dir, broken).get())
    }

    @Test fun onlyProxyAddressesAreAccepted() {
        for (ok in listOf("", "http://127.0.0.1:7890", "https://proxy.example.com:443", "socks5h://u:p@[::1]:1080", "HTTP://host:80/")) {
            assertTrue(ok, NetProxyStore.valid(ok))
        }
        for (bad in listOf("127.0.0.1:7890", "ftp://h:21", "http://host", "http://host:0", "http://host:70000", "http://h:80/path", "http://h :80", "http://h:80?x=1")) {
            assertFalse(bad, NetProxyStore.valid(bad))
        }
    }

    @Test fun displayLeavesOutCredentials() {
        assertEquals("http://10.0.0.2:8080", NetProxyStore.display("http://alice:s3cret@10.0.0.2:8080"))
        assertEquals("socks5://127.0.0.1:1080", NetProxyStore.display("socks5://127.0.0.1:1080"))
    }

    /** 另一把「密钥」:按字节加一,和 [cipher] 解不通。 */
    private val legacyCipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { (plain[it] + 1).toByte() }
        override fun decrypt(data: ByteArray) = ByteArray(data.size) { (data[it] - 1).toByte() }
    }

    /** 旧版只给容器用时存的代理:升级后照样读得出,改存成新文件、删掉旧的,之后只认新的。 */
    @Test fun containerOnlySettingIsCarriedOver() {
        val old = File(dir, "container-proxy.bin")
        old.writeBytes(legacyCipher.encrypt("socks5://bob:pw@10.0.0.9:1080".toByteArray()))
        var changes = 0
        val store = NetProxyStore(dir, cipher, legacyCipher) { changes++ }
        assertEquals("socks5://bob:pw@10.0.0.9:1080", store.get())
        assertFalse(old.exists())
        assertTrue(File(dir, "net-proxy.bin").isFile)
        assertEquals("读的时候不算改了设置", 0, changes)
        // 重启后(没有旧文件了)照样是这一个
        assertEquals("socks5://bob:pw@10.0.0.9:1080", NetProxyStore(dir, cipher, legacyCipher).get())
        store.set("http://127.0.0.1:7890")
        assertEquals(1, changes)
    }

    /** 新的已经有了:旧文件不覆盖它。 */
    @Test fun newSettingWinsOverLeftoverLegacyFile() {
        NetProxyStore(dir, cipher).set("http://10.0.0.2:8080")
        File(dir, "container-proxy.bin").writeBytes(legacyCipher.encrypt("http://old:1".toByteArray()))
        assertEquals("http://10.0.0.2:8080", NetProxyStore(dir, cipher, legacyCipher).get())
    }

    /** 旧文件解不开:当没设,不报错。 */
    @Test fun unreadableLegacyFileCountsAsNotSet() {
        File(dir, "container-proxy.bin").writeBytes(byteArrayOf(9, 9))
        val broken = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(data: ByteArray): ByteArray = throw IllegalStateException("key gone")
        }
        assertEquals("", NetProxyStore(dir, cipher, broken).get())
    }
}
