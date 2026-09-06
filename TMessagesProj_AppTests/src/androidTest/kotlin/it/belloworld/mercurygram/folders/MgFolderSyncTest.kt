package it.belloworld.mercurygram.folders

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.TLRPC

/**
 * The Saved Messages blob (MercurygramFolders.md) must survive a round trip
 * through the codec unchanged, since a byte-equal canonical form is what tells
 * the sync that the cloud already holds the same set.
 */
class MgFolderSyncTest {

    private fun folder(): MessagesController.DialogFilter = MessagesController.DialogFilter().apply {
        id = -3
        name = "Work extra"
        color = 3
        flags = MessagesController.DIALOG_FILTER_FLAG_GROUPS or MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED
        alwaysShow.add(-1001234567890L)
        alwaysShow.add(123456789L)
        neverShow.add(-987654321L)
        pinnedDialogs.put(-1001234567890L, 0)
        title_noanimate = true
        entities.add(TLRPC.TL_messageEntityCustomEmoji().apply {
            offset = 0
            length = 4
            document_id = 5307685888880885312L
        })
    }

    /** The icon this client cannot show, kept as it was read (see MgFolderSync). */
    private fun icons(): JSONObject = JSONObject("""{"-3": "\uD83D\uDC31"}""")

    @Test
    fun roundTripIsCanonical() {
        val json = MgFolderSync.toJson(listOf(folder()), 1757181000, icons())
        val entry = json.getJSONArray("folders").getJSONObject(0)
        val parsed = MgFolderSync.fromJson(entry)
        assertEquals(-3, parsed.id)
        assertEquals("Work extra", parsed.name)
        assertEquals(3, parsed.color)
        assertEquals(folder().flags, parsed.flags)
        assertEquals(listOf(-1001234567890L, 123456789L), parsed.alwaysShow)
        assertEquals(listOf(-987654321L), parsed.neverShow)
        assertEquals(0, parsed.pinnedDialogs.get(-1001234567890L))
        assertEquals(true, parsed.title_noanimate)
        assertEquals(1, parsed.entities.size)
        // Read through a double this would come back as a different emoji.
        assertEquals(5307685888880885312L, (parsed.entities[0] as TLRPC.TL_messageEntityCustomEmoji).document_id)
        // Digits, not a JSON number: a number here would read back rounded.
        assertEquals("5307685888880885312", entry.getJSONArray("title_entities").getJSONObject(0).getString("document_id"))
        assertEquals(json.toString(), MgFolderSync.toJson(listOf(parsed), 1757181000, icons()).toString())
        assertEquals(1, json.getInt("version"))
        assertEquals(true, entry.getJSONObject("flags").getBoolean("groups"))
    }

    /** This client has no folder icon of its own: what it read is what it writes. */
    @Test
    fun iconsPassThrough() {
        val entry = MgFolderSync.toJson(listOf(folder()), 1, icons()).getJSONArray("folders").getJSONObject(0)
        assertEquals("\uD83D\uDC31", entry.getString("emoticon"))
        val plain = MgFolderSync.toJson(listOf(folder()), 1, JSONObject()).getJSONArray("folders").getJSONObject(0)
        assertEquals(false, plain.has("emoticon"))
    }

    @Test
    fun titleEntitiesOutsideTheTitleAreDropped() {
        val entity = { offset: Int, length: Int, id: String ->
            JSONObject("""{"id": -3, "title": "Work extra", "title_entities":
                [{"offset": $offset, "length": $length, "document_id": "$id"}]}""")
        }
        assertEquals(1, MgFolderSync.fromJson(entity(6, 4, "12")).entities.size)
        assertEquals(0, MgFolderSync.fromJson(entity(10, 1, "12")).entities.size)
        assertEquals(0, MgFolderSync.fromJson(entity(-1, 2, "12")).entities.size)
        assertEquals(0, MgFolderSync.fromJson(entity(9, 2, "12")).entities.size)
        assertEquals(0, MgFolderSync.fromJson(entity(0, 0, "12")).entities.size)
        assertEquals(0, MgFolderSync.fromJson(entity(0, 4, "0")).entities.size)
        assertEquals(0, MgFolderSync.fromJson(entity(0, 4, "nope")).entities.size)
    }

    /** A document written before these keys existed still reads (version stays 1). */
    @Test
    fun olderDocumentsStillParse() {
        val parsed = MgFolderSync.fromJson(JSONObject("""{"id": -3, "title": "Work extra"}"""))
        assertEquals(false, parsed.title_noanimate)
        assertEquals(0, parsed.entities.size)
    }

    @Test
    fun secretChatsStayOffTheWire() {
        val withSecret = folder().apply {
            alwaysShow.add(DialogObject.makeEncryptedDialogId(7))
            neverShow.add(DialogObject.makeEncryptedDialogId(8))
            pinnedDialogs.put(DialogObject.makeEncryptedDialogId(7), 1)
        }
        val entry = MgFolderSync.toJson(listOf(withSecret), 1, JSONObject()).getJSONArray("folders").getJSONObject(0)
        assertEquals("[-1001234567890,123456789]", entry.getJSONArray("include").toString())
        assertEquals("[-987654321]", entry.getJSONArray("exclude").toString())
        assertEquals("[-1001234567890]", entry.getJSONArray("pinned").toString())
    }

    @Test
    fun rejectsServerIds() {
        assertThrows(IllegalArgumentException::class.java) {
            MgFolderSync.fromJson(JSONObject("""{"id": 2, "title": "x"}"""))
        }
    }

    @Test
    fun rejectsReservedId() {
        assertThrows(IllegalArgumentException::class.java) {
            MgFolderSync.fromJson(JSONObject("""{"id": -1, "title": "x"}"""))
        }
    }
}
