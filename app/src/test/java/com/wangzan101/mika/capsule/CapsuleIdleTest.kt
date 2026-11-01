package com.wangzan101.mika.capsule

import com.wangzan101.mika.chat.CurrentChat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 点开空闲图标时卡片接着哪个对话:用户在 App 里所在的那个([CurrentChat]),没有记录时接着最近那个(调用方读出来的兜底),
 * 新开还没说话的对话是一张新对话卡片。纯逻辑,不读盘。
 */
class CapsuleIdleTest {
    private val recent = CardView.Chat("c-old", title = "订票")

    @Test fun noRecordFallsBackToTheMostRecentConversation() {
        assertEquals(recent, CapsuleIdle.chat(null, recent))
        // 一个对话都没有:新对话卡片
        assertEquals(CardView.Chat(null), CapsuleIdle.chat(null, null))
    }

    @Test fun aBrandNewConversationIsAFreshCard() {
        val card = CapsuleIdle.chat(CurrentChat.Fresh, recent)
        assertEquals(CardView.Chat(null, fresh = true), card)
        // 没有编号:在卡片里说话时由调用方新开一个
        assertEquals(null, card.target)
    }

    @Test fun theConversationOnScreenWins() {
        val asked = mutableListOf<String>()
        val card = CapsuleIdle.chat(CurrentChat.Open("c-new"), recent) { id -> asked += id; "查快递" }
        assertEquals(CardView.Chat("c-new", title = "查快递", current = true), card)
        // 只查它的标题:兜底那个的标题用不上
        assertEquals(listOf("c-new"), asked)
        // 记着它是「用户此刻在的那个」,卡片那边据此不让旧任务压过来
        assertEquals(true, card.current)
    }

    /** 没有标题的对话(编号已有、还没起好名字)照样接着它,只是卡片顶上不写标题。 */
    @Test fun aConversationWithoutATitleIsStillContinued() {
        val card = CapsuleIdle.chat(CurrentChat.Open("c-new"), recent) { null }
        assertEquals(CardView.Chat("c-new", title = null, current = true), card)
        assertEquals("c-new", card.target)
    }
}
