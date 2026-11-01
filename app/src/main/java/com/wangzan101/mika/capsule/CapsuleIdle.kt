package com.wangzan101.mika.capsule

import com.wangzan101.mika.chat.CurrentChat

/**
 * 点开空闲图标时卡片接着哪个对话(纯逻辑):用户在 App 里所在的那个([current],见 [CurrentChat]);没有记录时接着
 * 调用方读出来的最近那个([recent],进程刚起来、旧版本没有记录时才有);两个都没有就是一张新对话卡片。
 *
 * [title] 给出对话 [CurrentChat.Open.id] 的标题(用户起的或第一句话),还没有时返回 null。
 */
object CapsuleIdle {
    fun chat(current: CurrentChat?, recent: CardView.Chat?, title: (String) -> String? = { null }): CardView.Chat = when (current) {
        null -> recent ?: CardView.Chat(null)
        CurrentChat.Fresh -> CardView.Chat(null, fresh = true)
        // 记着它是「用户此刻在的那个」:别的对话里已结束、还挂着的结果不把卡片抢过去(见 [CapsuleCards.of])
        is CurrentChat.Open -> CardView.Chat(current.id, title = title(current.id), current = true)
    }
}
