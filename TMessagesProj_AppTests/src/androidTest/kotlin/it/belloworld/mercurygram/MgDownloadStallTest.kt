package it.belloworld.mercurygram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MgDownloadStallTest {

    private val stall = MgDownloadStall.STALL_MS

    @Test
    fun aRecentChunkKeepsTheOperationAlive() {
        assertEquals(3_000L, MgDownloadStall.msUntilStall(0, 5_000, 12_000))
    }

    @Test
    fun noChunkSinceStartStallsAfterTheWindow() {
        assertEquals(0L, MgDownloadStall.msUntilStall(1_000, 0, 1_000 + stall))
        assertTrue(MgDownloadStall.msUntilStall(1_000, 0, 1_001 + stall) < 0)
    }

    @Test
    fun aChunkFromBeforeARestartDoesNotCount() {
        // Paused and started again: the window counts from the new start.
        assertEquals(stall - 500, MgDownloadStall.msUntilStall(20_000, 2_000, 20_500))
    }

    @Test
    fun aChunkAfterAStallReleasesIt() {
        assertTrue(MgDownloadStall.msUntilStall(0, 0, 30_000) < 0)
        assertEquals(stall, MgDownloadStall.msUntilStall(0, 30_000, 30_000))
    }
}
