package it.belloworld.mercurygram

import android.content.ContentValues
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.Utilities
import org.telegram.tgnet.NativeByteBuffer
import org.telegram.tgnet.TLRPC

class MgMessageHistoryTest {

    // Hardcoded — MgMessageHistory.DB_NAME and its TBL_* constants are
    // private. Mirroring them in the test is safer than reflection and
    // they're part of the on-disk contract documented in AGENTS.md.
    private val dbName = "mg_message_history.db"
    private val tblDeleted = "deleted_messages"
    private val tblEdited = "edited_messages"

    private val testAccount = 0
    private val testDialog = 42L
    private val testMid = 100

    @Before
    fun resetDb() {
        // Triggers DbHelper.onCreate on the first call of this run (creates
        // both tables) and wipes residue from any previous test invocation.
        MgMessageHistory.getInstance().clearAll()
    }

    @After
    fun cleanupDb() {
        MgMessageHistory.getInstance().clearAll()
    }

    // --- ToS guard ---------------------------------------------------------

    @Test
    fun isExcluded_nullMessage_returnsTrue() {
        assertTrue(MgMessageHistory.isExcluded(testDialog, null))
    }

    @Test
    fun isExcluded_plainMessage_returnsFalse() {
        assertFalse(MgMessageHistory.isExcluded(testDialog, newMessage()))
    }

    @Test
    fun isExcluded_ttlMessage_returnsTrue() {
        val msg = newMessage().apply { ttl = 30 }
        assertTrue(MgMessageHistory.isExcluded(testDialog, msg))
    }

    @Test
    fun isExcluded_destroyTimeMessage_returnsTrue() {
        val msg = newMessage().apply { destroyTime = 1 }
        assertTrue(MgMessageHistory.isExcluded(testDialog, msg))
    }

    @Test
    fun isExcluded_encryptedDialog_returnsTrue() {
        val encDialogId = DialogObject.makeEncryptedDialogId(0)
        assertTrue(MgMessageHistory.isExcluded(encDialogId, newMessage()))
    }

    // --- Direct DB roundtrip ----------------------------------------------
    //
    // The public archive* paths post onto MessagesStorage's serial queue and
    // read from `messages_v2`; standing that up in a test would require a
    // logged-in account. Bypass it by seeding mg_message_history.db directly
    // (same on-disk schema as DbHelper.onCreate) and exercising the public
    // load surface.

    @Test
    fun archiveAndLoadRoundtrip_returnsStoredMessage() {
        val blob = serialize(newMessage())
        val now = System.currentTimeMillis()

        ApplicationLoader.applicationContext
            .openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
                db.insert(tblDeleted, null, row(testMid, blob, now))
                // Older pre-edit, newer pre-edit — getEditHistoryFor sorts ASC by when_ms.
                db.insert(tblEdited, null, row(testMid, blob, now - 1_000))
                db.insert(tblEdited, null, row(testMid, blob, now - 500))
            }

        val deleted = MgMessageHistory.getInstance()
            .getDeletedEntries(testAccount, testDialog)
        assertEquals(1, deleted.size)
        assertEquals(testMid, deleted[0].mid)
        assertEquals(MgMessageHistory.KIND_DELETED, deleted[0].kind)
        assertNotNull(deleted[0].message)
        assertEquals(testMid, deleted[0].message.id)

        val edits = MgMessageHistory.getInstance()
            .getEditHistoryFor(testAccount, testDialog, testMid)
        assertEquals(2, edits.size)
        assertTrue("edit history must be ASC by when_ms", edits[0].whenMs <= edits[1].whenMs)
        assertEquals(MgMessageHistory.KIND_EDITED, edits[0].kind)

        val editedMids = MgMessageHistory.getInstance()
            .getMidsForDialog(testAccount, testDialog, true)
        assertEquals(setOf(testMid), editedMids)

        val all = MgMessageHistory.getInstance().getEntries(testAccount, testDialog)
        assertEquals(3, all.size)
        // getEntries sorts DESC by whenMs — deleted row (now) must come first.
        assertEquals(MgMessageHistory.KIND_DELETED, all[0].kind)
        assertTrue(all[0].whenMs >= all[1].whenMs)
        assertTrue(all[1].whenMs >= all[2].whenMs)
    }

    @Test
    fun archiveAndLoadRoundtrip_unrelatedDialogIsolated() {
        val blob = serialize(newMessage())
        ApplicationLoader.applicationContext
            .openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
                db.insert(tblDeleted, null, row(testMid, blob, System.currentTimeMillis()))
            }
        // Same account, different dialog → empty.
        val otherDialog = 99L
        assertTrue(MgMessageHistory.getInstance()
            .getDeletedEntries(testAccount, otherDialog).isEmpty())
        assertTrue(MgMessageHistory.getInstance()
            .getMidsForDialog(testAccount, otherDialog, false).isEmpty())
    }

    @Test
    fun forgetDeleted_removesOnlyTheGivenMid() {
        val blob = serialize(newMessage())
        val otherMid = testMid + 1
        ApplicationLoader.applicationContext
            .openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
                db.insert(tblDeleted, null, row(testMid, blob, System.currentTimeMillis()))
                db.insert(tblDeleted, null, row(otherMid, blob, System.currentTimeMillis()))
            }

        MgMessageHistory.getInstance().forgetDeleted(testAccount, testDialog, listOf(testMid))
        // forgetDeleted posts on the serial globalQueue; a latch posted after it runs once it is done.
        val done = CountDownLatch(1)
        Utilities.globalQueue.postRunnable { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals(setOf(otherMid), MgMessageHistory.getInstance()
            .getMidsForDialog(testAccount, testDialog, false))
    }

    // --- Remote-delete marking ---------------------------------------------

    @Test
    fun takeRemote_returnsMarkedMidsOnce() {
        val history = MgMessageHistory.getInstance()
        history.markRemote(testDialog, listOf(testMid, testMid + 1))
        history.markRemote(testDialog, listOf(testMid + 2))
        assertEquals(setOf(testMid, testMid + 1, testMid + 2), history.takeRemote(testDialog))
        assertTrue(history.takeRemote(testDialog).isEmpty())
        assertTrue(history.takeRemote(testDialog + 1).isEmpty())
    }

    // --- helpers -----------------------------------------------------------

    private fun newMessage(): TLRPC.TL_message = TLRPC.TL_message().apply {
        id = testMid
        peer_id = TLRPC.TL_peerUser().apply { user_id = 12345L }
        date = (System.currentTimeMillis() / 1000).toInt()
        message = "hello"
        ttl = 0
        destroyTime = 0
    }

    private fun serialize(msg: TLRPC.TL_message): ByteArray {
        val size = msg.objectSize
        val nbb = NativeByteBuffer(size)
        try {
            msg.serializeToStream(nbb)
            val bytes = ByteArray(size)
            nbb.buffer.position(0)
            nbb.buffer.get(bytes)
            return bytes
        } finally {
            nbb.reuse()
        }
    }

    private fun row(mid: Int, data: ByteArray, whenMs: Long): ContentValues =
        ContentValues().apply {
            put("account", testAccount)
            put("dialog_id", testDialog)
            put("mid", mid)
            put("data", data)
            put("when_ms", whenMs)
        }
}
