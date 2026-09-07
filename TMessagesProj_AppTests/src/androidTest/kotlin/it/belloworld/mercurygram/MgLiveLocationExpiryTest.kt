package it.belloworld.mercurygram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC

/**
 * A live location shared "until stopped" carries period 0x7FFFFFFF, and date + period overflows an
 * int to a negative value, which read as "already expired". isExpiredLiveLocation() is the single
 * place every caller asks that question, so it is the single place the widening has to hold.
 */
class MgLiveLocationExpiryTest {

    private val now = 1_700_000_000

    private fun liveLocation(date: Int, period: Int): TLRPC.Message = TLRPC.TL_message().apply {
        id = 1
        this.date = date
        peer_id = TLRPC.TL_peerUser().apply { user_id = 9 }
        from_id = TLRPC.TL_peerUser().apply { user_id = 9 }
        media = TLRPC.TL_messageMediaGeoLive().apply {
            this.period = period
            geo = TLRPC.TL_geoPoint().apply { lat = 45.0; _long = 9.0 }
        }
    }

    @Test
    fun sharedUntilStoppedNeverExpires() {
        assertFalse(MessageObject.isExpiredLiveLocation(liveLocation(now - 86400, 0x7FFFFFFF), now))
        // still live well past any real sharing period
        assertFalse(MessageObject.isExpiredLiveLocation(liveLocation(1, 0x7FFFFFFF), Int.MAX_VALUE - 1))
    }

    @Test
    fun boundedShareExpiresOnTime() {
        assertFalse(MessageObject.isExpiredLiveLocation(liveLocation(now - 100, 900), now))
        assertTrue(MessageObject.isExpiredLiveLocation(liveLocation(now - 900, 900), now))
        assertTrue(MessageObject.isExpiredLiveLocation(liveLocation(now - 901, 900), now))
    }

    @Test
    fun largePeriodDoesNotWrapNegative() {
        // any period that overflows the int sum, not just the "until stopped" sentinel
        assertFalse(MessageObject.isExpiredLiveLocation(liveLocation(now, Int.MAX_VALUE - 1), now + 86400))
    }
}
