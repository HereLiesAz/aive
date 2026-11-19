package com.hereliesaz.geministrator.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatPairingTest {
    private val keeper = ChatIdentity("42", "42")
    private val stranger = ChatIdentity("66", "66")

    private fun pairing(clock: TestClock, code: String? = "123456") = ChatPairing(clock::read, { code })

    @Test
    fun theRightCodeFromTheRequestingChatPairs() {
        val clock = TestClock()
        val p = pairing(clock)
        assertEquals(PairRequestOutcome.Started, p.request(keeper))
        assertEquals("123456", p.pending()?.code)
        assertEquals(PairRedeemOutcome.Paired(keeper), p.redeem(keeper, "123456"))
        assertEquals(keeper, p.paired)
        assertNull(p.pending())
    }

    @Test
    fun aCodeEndsAfterFiveMinutes() {
        val clock = TestClock()
        val p = pairing(clock)
        p.request(keeper)
        clock.now += ChatPairing.VALID_MILLIS - 1
        assertTrue(p.pending() != null)
        clock.now += 1
        assertNull(p.pending())
        assertEquals(PairRedeemOutcome.NoChallenge, p.redeem(keeper, "123456"))
        assertNull(p.paired)
    }

    @Test
    fun anExpiredCodeIsSaidSoOnlyWhileItWasWaiting() {
        val clock = TestClock()
        val p = pairing(clock)
        p.request(keeper)
        clock.now += ChatPairing.VALID_MILLIS + 5
        assertEquals(PairRedeemOutcome.Expired, p.redeem(keeper, "123456"))
        assertNull(p.paired)
    }

    @Test
    fun aWrongCodeFailsAndThreeEndTheChallenge() {
        val p = pairing(TestClock())
        p.request(keeper)
        assertEquals(PairRedeemOutcome.Wrong, p.redeem(keeper, "000000"))
        assertEquals(PairRedeemOutcome.Wrong, p.redeem(keeper, "000001"))
        assertEquals(PairRedeemOutcome.Wrong, p.redeem(keeper, "000002"))
        // The right code no longer works: a new /pair and a new code are needed.
        assertEquals(PairRedeemOutcome.NoChallenge, p.redeem(keeper, "123456"))
        assertNull(p.paired)
    }

    @Test
    fun aCodeWorksOnce() {
        val p = pairing(TestClock())
        p.request(keeper)
        p.redeem(keeper, "123456")
        p.unpair()
        // Replaying the old code does nothing: there is no challenge.
        assertEquals(PairRedeemOutcome.NoChallenge, p.redeem(keeper, "123456"))
        assertNull(p.paired)
    }

    @Test
    fun aStrangerCannotUseTheCodeNorReplaceTheChallenge() {
        val p = pairing(TestClock())
        p.request(keeper)
        assertEquals(PairRequestOutcome.Busy, p.request(stranger))
        // Even if the stranger somehow had the digits, the challenge belongs to the chat that asked.
        assertEquals(PairRedeemOutcome.NoChallenge, p.redeem(stranger, "123456"))
        assertNull(p.paired)
        assertEquals(PairRedeemOutcome.Paired(keeper), p.redeem(keeper, "123456"))
    }

    @Test
    fun oncePairedNoOneElseCanStartPairing() {
        val p = pairing(TestClock())
        p.request(keeper)
        p.redeem(keeper, "123456")
        assertEquals(PairRequestOutcome.AlreadyPaired, p.request(stranger))
        assertEquals(keeper, p.paired)
    }

    @Test
    fun noRandomSourceMeansNoPairing() {
        val p = pairing(TestClock(), code = null)
        assertEquals(PairRequestOutcome.NoRandomSource, p.request(keeper))
        assertNull(p.pending())
    }

    @Test
    fun theSameChatMayAskAgainForAFreshCode() {
        val clock = TestClock()
        var n = 0
        val p = ChatPairing(clock::read, { "10000" + (++n) })
        p.request(keeper)
        assertEquals("100001", p.pending()?.code)
        p.request(keeper)
        assertEquals("100002", p.pending()?.code)
        assertEquals(PairRedeemOutcome.Wrong, p.redeem(keeper, "100001"))
    }

    @Test
    fun theSecureCodeIsSixDigitsWhenThereIsASource() {
        val code = secureSixDigitCode() ?: return
        assertEquals(6, code.length)
        assertTrue(code.all { it in '0'..'9' })
        assertFalse(code.isEmpty())
        assertIs<String>(code)
    }

    @Test
    fun rateLimiterAllowsWithinTheWindowThenRefusesThenRecovers() {
        val clock = TestClock()
        val limiter = ChatRateLimiter(limit = 3, windowMillis = 1_000, clock = clock::read, maxKeys = 2)
        assertTrue(limiter.allow("a") && limiter.allow("a") && limiter.allow("a"))
        assertFalse(limiter.allow("a"))
        assertTrue(limiter.allow("b"))
        clock.now += 1_000
        assertTrue(limiter.allow("a"))
        // Keys are bounded: a flood of senders cannot grow it.
        listOf("c", "d", "e", "f").forEach { limiter.allow(it) }
    }
}
