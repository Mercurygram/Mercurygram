package it.belloworld.mercurygram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC

/**
 * calculate() must re-derive its group state when an album loses a member, rather
 * than carry the previous run's answer over. See the comment on the resets there.
 */
class MgGroupedMessagesRecalcTest {

    private var nextId = 1

    private fun message(): TLRPC.Message = TLRPC.TL_message().apply {
        id = nextId++
        date = 1_700_000_000 + id
        peer_id = TLRPC.TL_peerChat().apply { chat_id = 7 }
        from_id = TLRPC.TL_peerUser().apply { user_id = 9 }
    }

    private fun documentMessage(): MessageObject {
        val msg = message()
        msg.media = TLRPC.TL_messageMediaDocument().apply {
            document = TLRPC.TL_document().apply {
                id = msg.id.toLong()
                mime_type = "application/pdf"
                attributes.add(TLRPC.TL_documentAttributeFilename().apply { file_name = "a.pdf" })
            }
        }
        return MessageObject(0, msg, false, false)
    }

    private fun photoMessage(): MessageObject {
        val msg = message()
        msg.media = TLRPC.TL_messageMediaPhoto().apply {
            photo = TLRPC.TL_photo().apply {
                id = msg.id.toLong()
                sizes.add(TLRPC.TL_photoSize().apply {
                    type = "x"
                    w = 800
                    h = 600
                    size = 1000
                })
            }
        }
        return MessageObject(0, msg, false, false)
    }

    @Test
    fun droppingTheLeadingDocumentClearsIsDocuments() {
        val group = MessageObject.GroupedMessages()
        val doc = documentMessage()
        group.messages.add(doc)
        group.messages.add(photoMessage())
        group.messages.add(photoMessage())

        group.calculate()
        assertTrue("leading document should mark the group", group.isDocuments)

        group.messages.remove(doc)
        group.calculate()

        assertFalse("isDocuments must be re-derived, not carried over", group.isDocuments)
        for (position in group.posArray) {
            assertTrue(
                "ph must stay a 0..1 fraction, was ${position.ph}",
                position.ph > 0f && position.ph <= 1f
            )
        }
    }

    /** The count<2 branch returns early, so the resets at the top are all that clears it. */
    @Test
    fun shrinkingToASingleMessageClearsSiblingState() {
        val group = MessageObject.GroupedMessages()
        group.messages.add(photoMessage())
        group.hasSibling = true
        group.isDocuments = true

        group.calculate()

        assertFalse("a one-message group has no siblings", group.hasSibling)
        assertFalse("isDocuments must be re-derived", group.isDocuments)
    }
}
