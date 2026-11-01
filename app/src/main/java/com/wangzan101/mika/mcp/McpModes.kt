package com.wangzan101.mika.mcp

import com.wangzan101.mika.settings.McpSettings
import com.wangzan101.mika.settings.McpUsbSettings
import com.wangzan101.mika.settings.RelaySettings

/**
 * 局域网和 USB 共用的本地监听:局域网开着就绑所有网卡,只有 USB 开着就只绑回环。
 * 远程网关用独立的出站通道,不改变本地监听范围;三个开关各管各的,通知「关闭」会一起关闭。
 * 不碰安卓接口,在电脑上测。
 */
object McpModes {
    /** 电脑经 adb 打开 USB 方式(`am start-foreground-service -a`)。 */
    const val ACTION_USB_ON = "com.wangzan101.mika.mcp.USB_ON"
    /** 电脑经 adb 关掉 USB 方式。 */
    const val ACTION_USB_OFF = "com.wangzan101.mika.mcp.USB_OFF"
    /** 通知上的「关闭」:本地监听和远程通道都关。 */
    const val ACTION_TURN_OFF = "com.wangzan101.mika.mcp.TURN_OFF"

    enum class Listen { Off, LoopbackOnly, PrivateNetworks }

    fun listen(network: Boolean, usb: Boolean): Listen = when {
        network -> Listen.PrivateNetworks
        usb -> Listen.LoopbackOnly
        else -> Listen.Off
    }

    /** 服务收到的动作改哪个开关;别的动作(含没有动作)不改,只按开关现状来。 */
    fun apply(action: String?, network: McpSettings, usb: McpUsbSettings, relay: RelaySettings? = null) {
        when (action) {
            ACTION_USB_ON -> usb.enable()
            ACTION_USB_OFF -> usb.disable()
            ACTION_TURN_OFF -> {
                try { network.setEnabled(false) }
                finally { try { usb.disable() } finally { relay?.setEnabled(false) } }
            }
        }
    }
}
