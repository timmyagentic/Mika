package com.wangzan101.mika.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** 手机上登记的一个识别服务,见 [SystemSpeech.services]。[component] 是「包名/类名」的短写。 */
data class RecognizerService(val component: String, val open: Boolean, val permission: String?)

/** 手机自带识别:能力检测、错误码怎么归类、识别用的语言。 */
object SystemSpeech {
    /**
     * 安卓 11 起要在清单里声明查询 RecognitionService,否则这里永远是不可用。
     * [silent] 是之前试过、服务不响应的记号(见 [SpeechSettings.systemSilent]):系统只能说有没有,理不理要试了才知道。
     */
    fun capability(context: Context, silent: Boolean = false): SystemCapability {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val online = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        return SystemCapability(available = online || onDevice, onDevice = onDevice, responsive = !silent)
    }

    /**
     * 识别服务的错误码归到哪种失败;null 表示「这一段没听到话」(没匹配上、没开口),按着时重开、松开后照已有的给结果。
     * 列表外的码(服务端错误、语言不支持等)按服务出了问题处理。
     */
    fun failureOf(code: Int): SpeechFailure? = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> SpeechFailure.Busy
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, ERROR_SERVER_DISCONNECTED -> SpeechFailure.Network
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.NoPermission
        SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_CLIENT -> SpeechFailure.MicUnavailable
        else -> SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone)
    }

    /** 一个错误码到了之后怎么办:重开、按已听到的收尾、报失败。 */
    sealed interface Outcome {
        data object Retry : Outcome
        data object Finish : Outcome
        data class Fail(val why: SpeechFailure) : Outcome
    }

    /**
     * [holding] 是手还按着,[heard] 是已经听到过字。已经听到的字不丢:松开后、或按着时中途出错,都按听到的收尾。
     * 松开后有的服务在 stopListening 之后报 ERROR_CLIENT,什么都没听到时那是没听清,不是麦克风坏了。
     */
    fun afterError(code: Int, holding: Boolean, heard: Boolean): Outcome {
        val failure = failureOf(code)
        return when {
            failure == null && holding -> Outcome.Retry
            failure == null || heard -> Outcome.Finish
            !holding && code == SpeechRecognizer.ERROR_CLIENT -> Outcome.Finish
            else -> Outcome.Fail(failure)
        }
    }

    /**
     * 开始听之后 [SystemSpeechSession.NO_RESPONSE_MS] 内识别服务一个回调都没给时怎么办。
     * [answered] 是这个识别器之前回过话(说明服务是好的,只是这一段卡住了):按已听到的收尾,什么都没听到就是没听清。
     * 从没回过话就是这台手机的识别服务不理第三方 App 的请求,再等也不会有字,报 [SpeechEngineSpec.Why.SystemNoResponse]。
     */
    fun afterSilence(answered: Boolean): Outcome =
        if (answered) Outcome.Finish else Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemNoResponse))

    /**
     * 手机上登记的识别服务(声明了 RecognitionService 的组件)。[RecognizerService.open] 是它对别的 App 开放(exported);
     * [RecognizerService.permission] 是绑定它要的权限。给诊断信息和 [attempts] 用。
     */
    fun services(context: Context): List<RecognizerService> = runCatching {
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map { r ->
            val s = r.serviceInfo
            RecognizerService(ComponentName(s.packageName, s.name).flattenToShortString(), s.exported, s.permission)
        }
    }.getOrDefault(emptyList())

    /**
     * 一次会话依次去试的识别服务,null 是系统默认的那个(不点名,由系统挑)。
     * 之前点名试过、回了话的([remembered],还在手机上)排最前;默认的一个回调都不给时,再点名试其余的,对别的 App 开放的先试。
     * 手机上只有一个服务时它就是默认的,不再点名重试;最多试 [MAX_ATTEMPTS] 个,免得按着的人干等。
     */
    fun attempts(services: List<RecognizerService>, remembered: String?): List<String?> {
        val known = remembered?.takeIf { r -> services.any { it.component == r } }
        val others = if (services.size < 2) emptyList() else
            services.filter { it.component != known }.sortedBy { !it.open }.map { it.component }
        return (listOfNotNull(known) + listOf(null) + others).take(MAX_ATTEMPTS)
    }

    /** 一次会话最多试几个识别服务(含默认的)。 */
    const val MAX_ATTEMPTS = 3

    /** 本机识别说这个语言用不了(没装语言包等):换成联网识别再试。 */
    internal fun languageMissing(code: Int) = code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE

    /** App 语言对应的识别语言(BCP-47);跟随系统时用系统的语言。界面是英文、系统也是英文时沿用系统的地区。 */
    fun speechTag(language: AppLanguage, system: Locale = Locale.getDefault()): String = when (language) {
        AppLanguage.SimplifiedChinese -> "zh-CN"
        AppLanguage.TraditionalChinese -> "zh-TW"
        AppLanguage.English -> if (system.language == "en") languageRegion(system) else "en-US"
        AppLanguage.System -> languageRegion(system)
    }

    /**
     * 只留「语言-地区」:系统语言常带文字子标签(zh-Hans-CN),识别服务普遍只认 zh-CN 这样的写法,
     * 多一段就可能当成不支持的语言。扩展、变体也一并去掉。
     */
    private fun languageRegion(locale: Locale): String =
        Locale.Builder().setLanguage(locale.language).setRegion(locale.country).build().toLanguageTag()

    /** 转写接口要的 ISO-639-1 语言代码。 */
    fun isoCode(tag: String): String = Locale.forLanguageTag(tag).language

    // 安卓 12 起才有的错误码;写成数值,低版本收不到也不影响
    private const val ERROR_TOO_MANY_REQUESTS = 10
    private const val ERROR_SERVER_DISCONNECTED = 11
    private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    private const val ERROR_LANGUAGE_UNAVAILABLE = 13
}

/**
 * 手机自带识别的一次会话。[SpeechRecognizer] 只能在主线程建和调,所以这里所有对它的操作都投到主线程。
 * 按着期间识别服务自己结束一段(出最终结果或「没听到」)就立刻重开,各段由 [Segments] 拼起来;
 * 松开后 stopListening,最多等 [FINAL_WAIT_MS] 拿最终结果,等不到就用最后一次中间结果。
 * 每次 startListening 后 [NO_RESPONSE_MS] 内服务一个回调都没有:手还按着、[attempts] 里还有没试的服务,就换下一个去听;
 * 都试过了按 [SystemSpeech.afterSilence] 收尾。从没回过话时调 [onNoResponse],回过话时调一次 [onAnswered] 并带上回话的服务
 * (null 是系统默认的):设置据此数连着几次不响应,够了才记下,也记住点名试通的那个。
 */
class SystemSpeechSession(
    context: Context,
    private val onDevice: Boolean,
    private val languageTag: String,
    private val onNoResponse: () -> Unit = {},
    private val onAnswered: (String?) -> Unit = {},
    /** 依次去试的识别服务,见 [SystemSpeech.attempts];null 是系统默认的。 */
    private val attempts: List<String?> = listOf(null),
) : SpeechSession {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val out = SessionEvents()
    private val segments = Segments()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true

    // 下面这些只在主线程读写
    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false
    /** 正在试 [attempts] 里的第几个。 */
    private var attempt = 0
    private var holding = true
    private val restarts = Restarts()
    private var restartPending = false
    /** 当前识别器回过话没有(任何回调都算)。 */
    private var answered = false
    /** 这次会话已经报过 [onAnswered];每次会话只报一次,免得每个音量回调都写设置。 */
    private var reported = false
    // 还没回过话时由 [silence] 收尾:松开后的等待不抢先给「没听清」
    private val timeout = Runnable { if (answered) finish(SpeechEvent.Final(segments.full())) }
    private val silence = Runnable {
        if (done()) return@Runnable
        if (!answered && holding && attempt + 1 < attempts.size) {
            // 这个服务一个回调都没给:手还按着,换下一个服务接着听
            attempt++
            open(preferOnDevice = false)
            return@Runnable
        }
        when (val o = SystemSpeech.afterSilence(answered)) {
            is SystemSpeech.Outcome.Fail -> {
                onNoResponse()
                finish(SpeechEvent.Failed(o.why))
            }
            else -> finish(SpeechEvent.Final(segments.full()))
        }
    }
    private val restart = Runnable {
        restartPending = false
        if (!done() && holding) listen()
    }

    init {
        onMain { open(onDevice && attempts.firstOrNull() == null) }
    }

    override fun release() = onMain {
        if (!holding || done()) return@onMain
        holding = false
        if (restartPending) {
            // 上一段已经结束、还没重开:没有在听的,直接按已听到的收尾
            finish(SpeechEvent.Final(segments.full()))
            return@onMain
        }
        recognizer?.stopListening()
        main.postDelayed(timeout, FINAL_WAIT_MS)
    }

    override fun cancel() {
        // 先关事件出口(任何线程都立刻生效),再到主线程收拾识别器
        if (!out.cancel()) return
        onMain {
            recognizer?.cancel()
            teardown()
        }
    }

    private fun done() = out.finished || out.cancelled

    private fun open(preferOnDevice: Boolean) {
        if (done()) return
        teardown()
        val r = if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }
        usingOnDevice = r != null
        // 点名的服务用它;没点名(或名字认不出)交给系统默认的
        val named = attempts.getOrNull(attempt)?.let(ComponentName::unflattenFromString)
        val rec = r ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context, named) }.getOrNull()
        if (rec == null) {
            finish(SpeechEvent.Failed(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone)))
            return
        }
        recognizer = rec
        answered = false
        rec.setRecognitionListener(listener)
        listen()
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // 联网识别时:手机有本机识别能力就请服务尽量离线识别
            if (!usingOnDevice && onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        main.removeCallbacks(silence)
        main.postDelayed(silence, NO_RESPONSE_MS)
        try {
            recognizer?.startListening(intent)
        } catch (e: RuntimeException) {
            finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        }
    }

    private fun finish(e: SpeechEvent) {
        main.removeCallbacks(timeout)
        out.finish(e)
        teardown()
    }

    /** 按着时这一段空了:按 [Restarts] 决定隔多久重开,或者不再重开、按已听到的收尾。重开总是投到主线程稍后做,不在回调里直接开。 */
    private fun emptySegment() {
        when (val d = restarts.onEmpty(android.os.SystemClock.elapsedRealtime())) {
            Restarts.Decision.GiveUp -> finish(SpeechEvent.Final(segments.full()))
            is Restarts.Decision.Restart -> {
                restartPending = true
                main.postDelayed(restart, d.delayMs)
            }
        }
    }

    /** 服务回了话:这一段不算没响应。 */
    private fun heard() {
        answered = true
        main.removeCallbacks(silence)
        if (!reported) {
            reported = true
            onAnswered(if (usingOnDevice) null else attempts.getOrNull(attempt))
        }
    }

    private fun teardown() {
        main.removeCallbacks(timeout)
        main.removeCallbacks(silence)
        main.removeCallbacks(restart)
        restartPending = false
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun firstText(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = heard()
        override fun onBeginningOfSpeech() = heard()
        override fun onBufferReceived(buffer: ByteArray?) = heard()
        override fun onEndOfSpeech() = heard()
        override fun onEvent(eventType: Int, params: Bundle?) = heard()

        override fun onRmsChanged(rmsdB: Float) {
            heard()
            if (done()) return
            // 识别服务给的大致在 -2..10 dB,压到 0..1 画波形
            out.send(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            heard()
            if (done()) return
            val text = firstText(partialResults)
            if (text.isBlank()) return
            restarts.onSpeech()
            out.send(Segments.split(segments.partial(text)))
        }

        override fun onResults(results: Bundle?) {
            heard()
            if (done()) return
            val text = firstText(results)
            segments.segmentDone(text)
            if (holding) {
                // 识别服务觉得一句说完了,但手还按着:接着听下一段
                if (text.isBlank()) {
                    emptySegment()
                } else {
                    restarts.onSpeech()
                    out.send(Segments.split(segments.full()))
                    restartPending = true
                    main.post(restart)
                }
            } else {
                finish(SpeechEvent.Final(segments.full()))
            }
        }

        override fun onError(error: Int) {
            heard()
            if (done()) return
            if (usingOnDevice && SystemSpeech.languageMissing(error) && segments.full().isEmpty()) {
                open(preferOnDevice = false)
                if (!holding && !done()) {
                    // 已经松开了:新识别器只收尾,等待上限重新算
                    recognizer?.stopListening()
                    main.postDelayed(timeout, FINAL_WAIT_MS)
                }
                return
            }
            when (val o = SystemSpeech.afterError(error, holding, heard = segments.full().isNotEmpty())) {
                SystemSpeech.Outcome.Retry -> emptySegment()
                SystemSpeech.Outcome.Finish -> finish(SpeechEvent.Final(segments.full()))
                is SystemSpeech.Outcome.Fail -> finish(SpeechEvent.Failed(o.why))
            }
        }
    }

    companion object {
        /** 松开后等最终结果的上限。 */
        const val FINAL_WAIT_MS = 2_000L

        /**
         * 开始听之后等识别服务第一个回调(通常是 onReadyForSpeech,正常几百毫秒内到)的上限。
         * 留出识别服务进程冷启动的时间;超过就当这一段没响应。
         */
        const val NO_RESPONSE_MS = 3_000L
    }
}
