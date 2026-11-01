package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.mcp.McpRpc
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.remote.RemoteAccess
import com.wangzan101.mika.core.remote.RemoteActivity
import com.wangzan101.mika.core.remote.RemoteHost
import com.wangzan101.mika.core.mcp.McpUsb
import com.wangzan101.mika.core.shell.HelperHandoff
import com.wangzan101.mika.device.AndroidDeviceAccess
import com.wangzan101.mika.core.remote.RemoteTasks
import com.wangzan101.mika.settings.McpSettings
import com.wangzan101.mika.settings.McpUsbSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** 电脑连接此刻的样子:关着、在监听哪个端口、或没能开起来(端口被占等)。 */
sealed interface McpServerState {
    data object Off : McpServerState
    data class Listening(val port: Int) : McpServerState
    data class Failed(val message: String) : McpServerState
}

/**
 * 电脑连接(MCP)的开关与服务:整个进程一份。两种方式:局域网([settings],用户在设置里打开,默认关)
 * 和 USB([usb],电脑用 adb 打开,只听回环),怎么合起来见 [McpModes]。有一种开着就起 [McpService]
 * (前台服务,让进程在后台也活着),服务在的时候才监听端口;都关了就停服务、关端口。任务经 [tasks] 交给任务服务。
 */
class McpRuntime(
    private val context: Context, val settings: McpSettings, val usb: McpUsbSettings, private val tasks: RemoteTasks,
    val device: AndroidDeviceAccess,
    private val history: com.wangzan101.mika.core.remote.RemoteHistory? = null,
) {
    private val _state = MutableStateFlow<McpServerState>(McpServerState.Off)
    val state: StateFlow<McpServerState> = _state.asStateFlow()
    @Volatile private var host: RemoteHost? = null
    private var hostListen = McpModes.Listen.Off

    /** 最近连过的客户端和最后一次请求;没在监听时为空。 */
    val activity: RemoteActivity get() = host?.activity?.value ?: RemoteActivity()

    fun listen(): McpModes.Listen = McpModes.listen(settings.enabled.value, usb.enabled.value)

    /** 设置页上局域网那种的开关。 */
    fun setEnabled(on: Boolean) {
        settings.setEnabled(on)
        apply()
    }

    /** 设置页上 USB 那种的开关(电脑经 adb 开关的走 [McpService] 的动作,同一套)。 */
    fun setUsbEnabled(on: Boolean) {
        if (on) usb.enable() else usb.disable()
        apply()
    }

    /** 开关变了:还有一种开着就让服务按新的开关重新监听,都关了就停服务。 */
    private fun apply() {
        if (listen() != McpModes.Listen.Off) ensureRunning() else {
            context.stopService(Intent(context, McpService::class.java))
            _state.value = McpServerState.Off
        }
    }

    /**
     * 有一种开着就把服务起来(已经在跑就让它按开关重新对一遍)。从后台起前台服务可能被系统拒绝,这时记成没开起来,
     * 等下次打开 Mika 的界面再起。
     */
    fun ensureRunning() {
        if (listen() == McpModes.Listen.Off) return
        runCatching { context.startForegroundService(Intent(context, McpService::class.java)) }
            .onFailure { e ->
                Log.w(TAG, "could not start computer access service", e)
                _state.value = McpServerState.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
    }

    /**
     * 由 [McpService] 调:按两个开关监听。已经按同样的方式在听就不动;方式变了(局域网开了或关了)就换一个监听。
     * USB 开着时监听起来就写连接信息给电脑,没开起来就删掉。都关着、或开不起来返回 false。
     */
    @Synchronized internal fun sync(): Boolean {
        val want = listen()
        if (host != null && hostListen != want) stopServer()
        if (want == McpModes.Listen.Off) return false
        val ok = host != null || startServer(want)
        if (ok && usb.enabled.value) runCatching { usb.publish(host!!.boundPort) }.onFailure { Log.w(TAG, "cannot write USB connection info", it) }
        else usb.unpublish()
        return ok
    }

    private fun startServer(want: McpModes.Listen): Boolean {
        val network = want == McpModes.Listen.PrivateNetworks
        val h = RemoteHost(tasks, { if (settings.enabled.value) settings.token() else null }, settings.port, allowAllInterfaces = network,
            serverVersion = BuildConfig.VERSION_NAME, onError = { Log.w(TAG, "computer access server error", it) },
            loopbackToken = usb::token, device = device.access, history = history)
        return try {
            h.start()
            host = h
            hostListen = want
            _state.value = McpServerState.Listening(h.boundPort)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not listen on port ${settings.port}", e)
            _state.value = McpServerState.Failed(e.message ?: e::class.simpleName.orEmpty())
            false
        }
    }

    @Synchronized internal fun stopServer() {
        host?.stop()
        host = null
        hostListen = McpModes.Listen.Off
        if (_state.value is McpServerState.Listening) _state.value = McpServerState.Off
    }

    /** 给电脑跑的「经 USB 连接」脚本:不带秘密,App 启动时写一次(外部存储不可用就跳过)。 */
    fun writeUsbScript() {
        val dir = context.getExternalFilesDir(null) ?: return
        val f = File(dir, McpUsb.SCRIPT)
        val text = McpUsb.script(
            scriptPath = shortPath(f.absolutePath),
            helperScript = File(dir, HelperHandoff.USB_SCRIPT).absolutePath,
            infoPath = File(dir, McpUsb.INFO_FILE).absolutePath,
            component = "${context.packageName}/${McpService::class.java.name}",
            onAction = McpModes.ACTION_USB_ON, offAction = McpModes.ACTION_USB_OFF, port = settings.port,
        )
        if (runCatching { f.readText() }.getOrNull() == text) return
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "rename failed" }
        }
    }

    /** 电脑上要跑的那条命令(设置页显示的就是它)。 */
    fun usbCommand(): String {
        val path = context.getExternalFilesDir(null)?.let { File(it, McpUsb.SCRIPT).absolutePath }
            ?: "/storage/emulated/0/Android/data/${context.packageName}/files/${McpUsb.SCRIPT}"
        return "adb shell sh " + shortPath(path)
    }

    /** 换令牌:旧的当场失效。 */
    fun regenerateToken(): String = settings.regenerate()

    companion object {
        private const val TAG = "MikaMcp"

        /** 主用户的外部存储在 shell 里也叫 /sdcard;给人看的命令用短写法(和「用电脑启动」的脚本一样)。 */
        private fun shortPath(path: String) = HelperHandoff.usbCommand(path).removePrefix("adb shell sh ")

        /** 电脑能连的地址:开着的网卡上的局域网、私有组网 IPv4 地址。 */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && RemoteAccess.privateAddress(it) }
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())

        fun url(address: String, port: Int) = "http://$address:$port${McpRpc.PATH}"

        /** 给电脑上的 MCP 客户端用的配置片段:地址加 Authorization 头。 */
        fun clientConfig(url: String, token: String): String = """
            {
              "mcpServers": {
                "mika": {
                  "type": "http",
                  "url": "$url",
                  "headers": { "Authorization": "Bearer $token" }
                }
              }
            }
        """.trimIndent()
    }
}
