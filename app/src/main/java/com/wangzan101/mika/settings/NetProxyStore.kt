package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.cap.web.WebProxy
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.net.ProxyAddress
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 设置 › 网络代理,形如 `http://[用户:密码@]主机:端口`;空串表示不用,直连。
 * 地址里可能带账号密码,所以和服务商的 Key 一样经 [KeyCipher] 加密后单独存一个文件,不进明文的偏好设置,也不写进日志。
 *
 * 谁用它:Mika 自己的连接(调模型、列模型、账号登录换令牌、下更新、内置浏览器)经 [NetProxy],本机和局域网地址直连;
 * 文件与计算环境里的命令在允许联网时经环境变量拿到它(见 `AndroidSandbox`)。
 *
 * 早先它只给容器用,存在 `container-proxy.bin`(另一把密钥):第一次读时用 [legacyCipher] 解出来、按新的存好,再删掉旧文件。
 */
class NetProxyStore(
    private val dir: File,
    private val cipher: KeyCipher,
    private val legacyCipher: KeyCipher? = null,
    /** 存了新值之后调(让已建好的连接按新设置重连)。 */
    private val onChange: () -> Unit = {},
) {
    private val file = File(dir, FILE)
    private val legacy = File(dir, LEGACY_FILE)
    @Volatile private var cached: String? = null

    /** 存着的代理;没有、或解不开(换过机、密钥没了)时为空串。 */
    @Synchronized fun get(): String {
        cached?.let { return it }
        migrate()
        val stored = runCatching { if (file.isFile) String(cipher.decrypt(file.readBytes()), Charsets.UTF_8) else "" }.getOrDefault("")
        return stored.also { cached = it }
    }

    /** 存下 [proxy](去掉两头空白);空的就删掉文件。调用方先用 [valid] 核对过。 */
    fun set(proxy: String) {
        synchronized(this) {
            val v = proxy.trim()
            require(valid(v)) { "not a proxy address" }
            if (v.isEmpty()) file.delete() else write(v)
            legacy.delete()
            cached = v
        }
        onChange()
    }

    /** 旧版只给容器用时存的那一份:解得开就按新的存好;新的已经有了、或解不开就不动它,之后存新值时一并删掉。 */
    private fun migrate() {
        val old = legacyCipher ?: return
        if (file.isFile || !legacy.isFile) return
        val v = runCatching { String(old.decrypt(legacy.readBytes()), Charsets.UTF_8) }.getOrNull() ?: return
        runCatching {
            if (v.isNotBlank()) write(v.trim())
            legacy.delete()
        }
    }

    private fun write(v: String) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeBytes(cipher.encrypt(v.toByteArray(Charsets.UTF_8)))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private const val FILE = "net-proxy.bin"
        private const val LEGACY_FILE = "container-proxy.bin"
        private const val KEY_ALIAS = "mika-net-proxy"
        private const val LEGACY_KEY_ALIAS = "mika-container-proxy"

        /** 空串(不用),或带协议、主机、端口的代理地址(http、https、socks5、socks5h),后面不带路径。 */
        fun valid(proxy: String): Boolean = ProxyAddress.valid(proxy)

        /** 给人看的写法:不带账号密码(那一行在设置页上随时看得到)。 */
        fun display(proxy: String): String = ProxyAddress.parse(proxy)?.display.orEmpty()

        @Volatile private var instance: NetProxyStore? = null

        /** 产品里的那一份;第一次取时把 [NetProxy.shared] 接上它。 */
        fun get(context: Context): NetProxyStore = instance ?: synchronized(this) {
            instance ?: NetProxyStore(
                File(context.applicationContext.filesDir, "settings"), KeystoreKeyCipher(KEY_ALIAS), KeystoreKeyCipher(LEGACY_KEY_ALIAS),
                onChange = { NetProxy.shared.changed(); WebProxy.apply(context.applicationContext) },
            ).also { store ->
                instance = store
                NetProxy.sharedSource = store::get
            }
        }
    }
}
