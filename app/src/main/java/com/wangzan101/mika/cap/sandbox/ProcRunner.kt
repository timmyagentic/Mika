package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.core.workspace.SandboxPaths
import java.io.File
import java.io.InputStream
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/** 一次进程运行的原始结果;[timedOut] 只表示 Kotlin 侧的兜底时限到了。 */
internal class ProcOutcome(val exitCode: Int, val output: ByteArray, val droppedBytes: Long, val timedOut: Boolean, val durationMs: Long)

/** 拼启动器 + PRoot 的命令行与环境变量。 */
object ProcRunner {
    const val OUTPUT_LIMIT = 1 shl 20
    const val READ_CHUNK = 64 * 1024
    const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    /** 走代理时本机、局域网地址照旧直连(和 Mika 自己的连接同一套,见 [LocalHosts])。 */
    const val NO_PROXY = LocalHosts.NO_PROXY

    /**
     * [cwd]:`/workspace` 下的相对路径,空串为 `/workspace`;[standIns]:`files/sandbox/proc/` 里的替身文件名,
     * 各自绑到 /proc/<名字>。/dev 只绑 [ProotDevices] 列出的设备(不绑整个 /dev,见那里的说明)。
     */
    fun argv(
        dirs: SandboxDirs, workspace: File, cwd: String, timeoutSec: Int, network: Boolean, standIns: List<String>, command: List<String>,
        devices: List<String> = ProotDevices.bindArgs(),
    ): List<String> = listOf(
        dirs.lib("libmikarun.so").path, "--timeout", timeoutSec.toString(), "--net", if (network) "on" else "off", "--",
        dirs.lib("libproot.so").path, "--kill-on-exit", "--link2symlink", "-0", "-r", dirs.rootfs.path,
    ) + devices + listOf("-b", "/proc", "-b", "/sys") +
        standIns.flatMap { listOf("-b", "${File(dirs.procStandIns, it).path}:/proc/$it") } +
        listOf(
            "-b", "${workspace.path}:${SandboxPaths.WORKSPACE}",
            "-b", "${dirs.skills.path}:${SandboxPaths.SKILLS}",
            "-w", if (cwd.isEmpty()) SandboxPaths.WORKSPACE else "${SandboxPaths.WORKSPACE}/$cwd",
        ) + command

    /** 环境先清空,只给这些;桥没开时不给 MIKA_BRIDGE / MIKA_TOKEN。 */
    fun env(
        dirs: SandboxDirs, bridgeName: String?, token: String?, conversationId: String,
        /** 容器的网络代理;空串表示不用(见 NetProxyStore)。不允许联网时调用方给空串。 */
        proxy: String = "",
    ): Map<String, String> = buildMap {
        put("PROOT_LOADER", dirs.lib("libproot_loader.so").path)
        put("PROOT_TMP_DIR", dirs.tmp.path)
        put("HOME", "/root")
        put("PATH", PATH)
        put("LANG", "C.UTF-8")
        put("TMPDIR", "/tmp")
        // 每次启动都读取手机当前时区;Alpine 的 libc 不会读取 Android 的系统时区。
        put("TZ", timezone())
        bridgeName?.let { put("MIKA_BRIDGE", it) }
        token?.let { put("MIKA_TOKEN", it) }
        put("MIKA_CONVERSATION", conversationId)
        // 代理只给容器里的命令用:常见工具认这几个变量(有的只认大写);本机地址不走代理
        if (proxy.isNotBlank()) {
            for (name in listOf("http_proxy", "https_proxy", "all_proxy")) {
                put(name, proxy)
                put(name.uppercase(), proxy)
            }
            put("no_proxy", NO_PROXY)
            put("NO_PROXY", NO_PROXY)
        }
    }

    /**
     * 手机时区写成沙箱里的 TZ。地区名(如 Asia/Shanghai)原样给出,由运行环境自带的 tzdata 解析,保留历史偏移与夏令时;
     * 固定偏移(GMT+08:00 之类)不能原样给:POSIX 的 TZ 里符号与它相反。整点的换成 Etc/GMT-8 这类 tzdata 名称,
     * 其余写成 POSIX 形式 `<+0545>-05:45`;读不到时区时用 UTC。
     */
    internal fun timezone(zone: () -> ZoneId = ZoneId::systemDefault): String {
        val z = runCatching(zone).getOrNull() ?: return "UTC"
        val fixed = z as? ZoneOffset ?: z.id.takeIf { it.startsWith("GMT") || it.startsWith("UT") }?.let { z.normalized() as? ZoneOffset }
        val s = fixed?.totalSeconds ?: return z.id
        if (s == 0) return "UTC"
        if (s % 3600 == 0 && s / 3600 in -12..14) return "Etc/GMT" + (if (s > 0) "-" else "+") + abs(s / 3600)
        val a = abs(s)
        val hm = String.format(Locale.ROOT, "%02d%02d", a / 3600, a / 60 % 60)
        val posix = String.format(Locale.ROOT, "%02d:%02d", a / 3600, a / 60 % 60) +
            (if (a % 60 != 0) String.format(Locale.ROOT, ":%02d", a % 60) else "")
        return if (s > 0) "<+$hm>-$posix" else "<-$hm>$posix"
    }

    /** 读到流结束,结果见 [OutputCollector]。 */
    internal fun collect(input: InputStream, limit: Int = OUTPUT_LIMIT): Pair<ByteArray, Long> =
        OutputCollector(limit).apply { drain(input) }.snapshot()
}

/** 读到流结束;最多留 [limit] 字节,多的读掉丢弃并计数(不读完命令会卡在写输出上)。读的途中也能取快照。 */
internal class OutputCollector(private val limit: Int = ProcRunner.OUTPUT_LIMIT) {
    private val kept = java.io.ByteArrayOutputStream(minOf(limit, ProcRunner.READ_CHUNK))
    private var dropped = 0L

    fun drain(input: InputStream) {
        val buf = ByteArray(ProcRunner.READ_CHUNK)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            synchronized(this) {
                val room = limit - kept.size()
                if (room > 0) kept.write(buf, 0, minOf(n, room))
                if (n > room) dropped += n - maxOf(room, 0)
            }
        }
    }

    @Synchronized fun snapshot(): Pair<ByteArray, Long> = kept.toByteArray() to dropped
}
