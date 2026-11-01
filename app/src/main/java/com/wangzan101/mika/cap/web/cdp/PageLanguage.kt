package com.wangzan101.mika.cap.web.cdp

import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * 网页按哪种语言请求。用户在 Mika 里选定了界面语言时,网页也按这一种要(Accept-Language 请求头,
 * 内核同时让 navigator.language(s) 跟着它);跟随系统时不动,内核发系统给的那串。
 * 经 `Emulation.setUserAgentOverride` 的 acceptLanguage 设:桌面版加在桌面身份上([withLanguage]),
 * 手机版要换成一份照搬默认手机身份、只多了语言的覆盖([mobileOverride]),UA 与 UA-CH 都不变。
 * JS 的 Intl 格式不改(`Emulation.setLocaleOverride` 是整个渲染进程一份,各标签抢着设、一个标签断开就撤掉),仍按系统。
 */
object PageLanguage {
    /**
     * 语言标签(补全过的,如 en-Latn-US)对应的语言列表:带地区的写成「语言-地区,语言」,
     * 文字体系不写(不少网站只认「语言-地区」);没有地区的只写语言。别的语言不往后加。
     * 只给列表、不写 q 值:内核按这份列表同时设 navigator.languages,并自己给请求头逐项加 q 值,写了会重复。
     * 安卓的 Locale 给印尼语等几种语言的是旧代码(in),网站认的是现行代码(id),写之前换过来。
     */
    fun acceptLanguage(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        val lang = AppLanguage.modernCode(l.language.ifEmpty { return tag })
        return if (l.country.isEmpty()) lang else "$lang-${l.country},$lang"
    }

    /** 给一份 UA 覆盖加上语言;[acceptLanguage] 为 null(跟随系统)时原样返回。 */
    fun withLanguage(override: JsonObject, acceptLanguage: String?): JsonObject =
        if (acceptLanguage == null) override else JsonObject(override + ("acceptLanguage" to JsonPrimitive(acceptLanguage)))

    /**
     * 手机版的 `Emulation.setUserAgentOverride` 参数。跟随系统时是空 UA,即撤销覆盖、回到 WebView 设置里的默认身份;
     * 选定了语言时照搬默认身份:[defaultUa] 是 WebView 的默认 UA,[metadata] 是改设置前取到的默认 UA-CH(CDP 的样子)。
     * 系统取不到默认 UA-CH(老的 WebView)时只带 UA,这时页面读到的 UA-CH 可能不全。
     */
    fun mobileOverride(defaultUa: String, metadata: JsonObject?, acceptLanguage: String?): JsonObject = buildJsonObject {
        if (acceptLanguage == null || defaultUa.isEmpty()) {
            put("userAgent", "")
            return@buildJsonObject
        }
        put("userAgent", defaultUa)
        put("acceptLanguage", acceptLanguage)
        metadata?.let { put("userAgentMetadata", it) }
    }
}
