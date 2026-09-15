package it.belloworld.mercurygram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.tgnet.TLRPC

/**
 * The stored order is the only copy of the pins the server refused, and every
 * server refresh clears the flags it writes back, so applying it has to survive
 * being run again and again on a list the server keeps resetting.
 */
class MgPinsTest {

    private fun dialog(dialogId: Long, pinned: Boolean = false, pinnedNum: Int = 0, folderId: Int = 0) =
        TLRPC.TL_dialog().apply {
            id = dialogId
            this.pinned = pinned
            this.pinnedNum = pinnedNum
            folder_id = folderId
        }

    private fun archiveRow() = TLRPC.TL_dialogFolder().apply {
        id = -1
        pinned = true
        pinnedNum = 42
    }

    /** Sorted the way MessagesController.dialogComparator orders pinned chats. */
    private fun order(dialogs: List<TLRPC.Dialog>) = dialogs
        .filter { it.pinned && it !is TLRPC.TL_dialogFolder }
        .sortedByDescending { it.pinnedNum }
        .map { it.id }

    @Test
    fun restoresPinsTheServerCleared() {
        val dialogs = listOf(dialog(1, pinned = true, pinnedNum = 1), dialog(2), dialog(3))
        val pins = arrayListOf(3L, 1L, 2L)

        assertFalse(MgPins.apply(pins, dialogs))
        assertEquals(listOf(3L, 1L, 2L), order(dialogs))
        assertTrue(dialogs[1].pinned)
    }

    @Test
    fun adoptsUnknownPinsAtTheTopInDisplayOrder() {
        val dialogs = listOf(
            dialog(1, pinned = true, pinnedNum = 5),
            dialog(2, pinned = true, pinnedNum = 9),
            dialog(3, pinned = true, pinnedNum = 7)
        )
        val pins = arrayListOf(3L)

        assertTrue(MgPins.apply(pins, dialogs))
        assertEquals(listOf(2L, 1L, 3L), pins)
        assertEquals(listOf(2L, 1L, 3L), order(dialogs))
    }

    @Test
    fun leavesTheArchiveRowAlone() {
        val dialogs = listOf(archiveRow(), dialog(1, pinned = true, pinnedNum = 1))
        val pins = arrayListOf<Long>()

        assertTrue(MgPins.apply(pins, dialogs))
        assertEquals(listOf(1L), pins)
        assertEquals(42, dialogs[0].pinnedNum)
    }

    @Test
    fun keepsIdsItCannotPlace() {
        val dialogs = listOf(dialog(1), dialog(2, folderId = 1), dialog(3))
        val pins = arrayListOf(1L, 2L, 3L, 4L)

        assertFalse(MgPins.apply(pins, dialogs))
        // The archived chat and the one not loaded yet stay listed, in case they come back.
        assertEquals(listOf(1L, 2L, 3L, 4L), pins)
        assertFalse(dialogs[1].pinned)
        assertEquals(listOf(1L, 3L), order(dialogs))
    }

    @Test
    fun runsAgainWithoutChangingAnything() {
        val dialogs = listOf(dialog(1, pinned = true, pinnedNum = 3), dialog(2), dialog(3))
        val pins = arrayListOf(2L, 3L)

        assertTrue(MgPins.apply(pins, dialogs))
        val adopted = ArrayList(pins)
        val ordered = order(dialogs)

        assertFalse(MgPins.apply(pins, dialogs))
        assertEquals(adopted, pins)
        assertEquals(ordered, order(dialogs))
    }
}
