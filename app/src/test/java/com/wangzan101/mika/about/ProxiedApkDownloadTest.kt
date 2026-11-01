package com.wangzan101.mika.about

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** 设了网络代理时更新包由 Mika 自己经代理下:请求确实发给代理,下完整才留下文件,出错不留半截。 */
class ProxiedApkDownloadTest {
    private lateinit var proxy: MockWebServer
    private val dir: File = Files.createTempDirectory("apk").toFile()

    @Before fun setUp() { proxy = MockWebServer().also { it.start() } }
    @After fun tearDown() { proxy.shutdown() }

    private fun client() = NetProxy { "http://${proxy.hostName}:${proxy.port}" }.client()

    @Test fun downloadsThroughTheProxyWithProgress() = runTest {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        proxy.enqueue(MockResponse().setBody(okio.Buffer().write(body)))
        val target = File(dir, "update.apk")
        val seen = mutableListOf<Pair<Long, Long>>()
        ProxiedApkDownload.fetch(client(), "http://releases.example.test/mika.apk", target) { done, total -> seen += done to total }
        assertTrue(target.readBytes().contentEquals(body))
        assertEquals("GET http://releases.example.test/mika.apk HTTP/1.1", proxy.takeRequest().requestLine)
        assertEquals(body.size.toLong() to body.size.toLong(), seen.last())
        assertFalse(File(dir, "update.apk.part").exists())
    }

    @Test fun failedDownloadLeavesNoFile() = runTest {
        proxy.enqueue(MockResponse().setResponseCode(404))
        val target = File(dir, "update.apk")
        try {
            ProxiedApkDownload.fetch(client(), "http://releases.example.test/mika.apk", target) { _, _ -> }
            fail("expected an error")
        } catch (e: IOException) {
            assertFalse(target.exists())
            assertFalse(File(dir, "update.apk.part").exists())
        }
    }

    @Test fun stateFollowsTheFile() {
        val d = ProxiedApkDownload(client())
        val target = File(dir, "update.apk")
        assertEquals(DownloadPhase.Failed, d.state(target).phase)
        target.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(DownloadPhase.Ready, d.state(target).phase)
    }
}
