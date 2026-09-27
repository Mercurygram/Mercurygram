package it.belloworld.mercurygram.folders

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.MessagesController

/**
 * A server folder takes pinned plus included chats up to the chats-per-folder
 * limit, and its pinned chats are already part of alwaysShow, so the pin check
 * on a folder tab must not count them twice.
 */
class MgFolderPinsTest {

    private val mc = MessagesController.getInstance(0)
    private val saved = IntArray(4)

    @Before
    fun setLimits() {
        saved[0] = mc.dialogFiltersChatsLimitDefault
        saved[1] = mc.dialogFiltersChatsLimitPremium
        saved[2] = mc.maxFolderPinnedDialogsCountDefault
        saved[3] = mc.maxFolderPinnedDialogsCountPremium
        mc.dialogFiltersChatsLimitDefault = 100
        mc.dialogFiltersChatsLimitPremium = 200
        mc.maxFolderPinnedDialogsCountDefault = 100
        mc.maxFolderPinnedDialogsCountPremium = 200
    }

    @After
    fun restoreLimits() {
        mc.dialogFiltersChatsLimitDefault = saved[0]
        mc.dialogFiltersChatsLimitPremium = saved[1]
        mc.maxFolderPinnedDialogsCountDefault = saved[2]
        mc.maxFolderPinnedDialogsCountPremium = saved[3]
    }

    private fun folder(id: Int, included: Int) = MessagesController.DialogFilter().apply {
        this.id = id
        for (i in 1..included) alwaysShow.add(i.toLong())
    }

    /** The check DialogsActivity runs before pinning. */
    private fun canPin(premium: Boolean, filter: MessagesController.DialogFilter, pinned: Int, newPins: Int, alreadyAdded: Int = 0): Boolean {
        val max = MgFolders.maxPinned(mc, premium, filter, pinned)
        return newPins + pinned - alreadyAdded <= max
    }

    @Test
    fun serverFolderCountsPinsOnce() {
        assertTrue(canPin(false, folder(2, 95), pinned = 6, newPins = 1))
        assertTrue(canPin(false, folder(2, 99), pinned = 6, newPins = 1))
        assertFalse(canPin(false, folder(2, 99), pinned = 6, newPins = 2))
    }

    @Test
    fun pinningAnIncludedChatAddsNothing() {
        assertTrue(canPin(false, folder(2, 100), pinned = 6, newPins = 1, alreadyAdded = 1))
    }

    @Test
    fun premiumUsesThePremiumChatsLimit() {
        assertTrue(canPin(true, folder(2, 150), pinned = 6, newPins = 1))
        assertFalse(canPin(false, folder(2, 150), pinned = 6, newPins = 1))
    }

    @Test
    fun mercurygramFolderHasNoChatLimit() {
        assertTrue(canPin(false, folder(-3, 300), pinned = 6, newPins = 1))
    }
}
