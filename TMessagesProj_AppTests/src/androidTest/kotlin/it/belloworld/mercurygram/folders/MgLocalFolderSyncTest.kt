package it.belloworld.mercurygram.folders

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController

/**
 * The Saved Messages blob (LocalFolders.md) must survive a round trip through
 * the codec unchanged, since a byte-equal canonical form is what tells the sync
 * that the cloud already holds the local set.
 */
class MgLocalFolderSyncTest {

    private fun folder(): MessagesController.DialogFilter = MessagesController.DialogFilter().apply {
        id = -3
        name = "Work extra"
        color = 3
        flags = MessagesController.DIALOG_FILTER_FLAG_GROUPS or MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED
        alwaysShow.add(-1001234567890L)
        alwaysShow.add(123456789L)
        neverShow.add(-987654321L)
        pinnedDialogs.put(-1001234567890L, 0)
    }

    @Test
    fun roundTripIsCanonical() {
        val json = MgLocalFolderSync.toJson(listOf(folder()), 1757181000)
        val parsed = MgLocalFolderSync.fromJson(json.getJSONArray("folders").getJSONObject(0))
        assertEquals(-3, parsed.id)
        assertEquals("Work extra", parsed.name)
        assertEquals(3, parsed.color)
        assertEquals(folder().flags, parsed.flags)
        assertEquals(listOf(-1001234567890L, 123456789L), parsed.alwaysShow)
        assertEquals(listOf(-987654321L), parsed.neverShow)
        assertEquals(0, parsed.pinnedDialogs.get(-1001234567890L))
        assertEquals(json.toString(), MgLocalFolderSync.toJson(listOf(parsed), 1757181000).toString())
        assertEquals(1, json.getInt("version"))
        assertEquals(true, json.getJSONArray("folders").getJSONObject(0).getJSONObject("flags").getBoolean("groups"))
    }

    @Test
    fun secretChatsStayOffTheWire() {
        val withSecret = folder().apply {
            alwaysShow.add(DialogObject.makeEncryptedDialogId(7))
            neverShow.add(DialogObject.makeEncryptedDialogId(8))
            pinnedDialogs.put(DialogObject.makeEncryptedDialogId(7), 1)
        }
        val entry = MgLocalFolderSync.toJson(listOf(withSecret), 1).getJSONArray("folders").getJSONObject(0)
        assertEquals("[-1001234567890,123456789]", entry.getJSONArray("include").toString())
        assertEquals("[-987654321]", entry.getJSONArray("exclude").toString())
        assertEquals("[-1001234567890]", entry.getJSONArray("pinned").toString())
    }

    @Test
    fun rejectsServerIds() {
        assertThrows(IllegalArgumentException::class.java) {
            MgLocalFolderSync.fromJson(JSONObject("""{"id": 2, "title": "x"}"""))
        }
    }
}
