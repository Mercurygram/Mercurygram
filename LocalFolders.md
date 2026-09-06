# Local folders

Telegram caps chat folders server-side (10 for free accounts, 20 with Premium,
100 / 200 chats per folder). Folder membership, however, is computed entirely
on the client: the server only stores the folder definition. Mercurygram
therefore supports **local folders**: ordinary folders whose definition never
reaches Telegram's folder API, so they count against no limit. Turn one on with
the "Local folder" switch when creating a folder; once the server limit is hit,
"Create new folder" goes straight to a local one.

Local folders are kept in step across devices through a single JSON document in
**Saved Messages**, edited in place. Telegram already knows every folder
definition on the account, so this leaks nothing new, and no third-party
service is involved. This file is the wire format, shared with the Desktop
client and with bots that resolve folders (for example ubot).

## The message

One document message in Saved Messages (`peer = inputPeerSelf`):

| Field | Value |
|---|---|
| `document.file_name` | `mercurygram-folders.json` |
| `document.mime_type` | `application/json` |
| caption | first line `#mercurygram_folders`, then optional human-readable lines `📁 <title> (<n> chats)`; at most 1024 characters, truncated at a line boundary |

**Discovery**: `messages.search(peer=inputPeerSelf, q="#mercurygram_folders",
filter=inputMessagesFilterDocument, limit=5)`, then keep the messages whose
document `file_name` matches and take the one with the highest id.

**Writing**: writers edit that message in place (`messages.editMessage` with a
new `inputMediaUploadedDocument` and the caption above; a user's own Saved
Messages have no edit time limit). When no message exists yet, send a new one.
Readers cache the message id and re-search on a miss. If two devices created a
message concurrently, the newest wins; writers may delete older ones.

## The document

UTF-8 JSON. Keep it under about 1 MB; a reader may refuse a document that is
much larger than that (Android stops at 4 MB), since anyone can drop a file
with that name into Saved Messages.

```json
{
  "format": "mercurygram-folders",
  "version": 1,
  "updated": 1757181000,
  "folders": [
    {
      "id": -3,
      "title": "Work extra",
      "color": 3,
      "flags": {
        "contacts": false, "non_contacts": false, "groups": true,
        "broadcasts": false, "bots": false,
        "exclude_muted": false, "exclude_read": false, "exclude_archived": true
      },
      "include": [-1001234567890, 123456789],
      "exclude": [-987654321],
      "pinned": [-1001234567890]
    }
  ]
}
```

| Key | Meaning |
|---|---|
| `format` | Always `mercurygram-folders`. |
| `version` | Format version; a bump means an incompatible change. Readers ignore documents with a version they do not know. Unknown keys are ignored. |
| `updated` | Unix seconds of the last write. Readers apply the document only when it is newer than the last `updated` they applied (last writer wins, whole document, no merge). Writers set it to `max(now, previous.updated + 1)`. |
| `folders` | Folder list; array order is the order of the local folders relative to each other. Their position among server folders is per device. |
| `id` | Negative integer, stable across devices. The namespace is disjoint from server folder ids (0 and >= 2). Pick it **at random** over the negative range rather than counting down from -1: a writer only knows its own folders, so two devices that each create a folder before they sync would otherwise both pick -1 and the reader would merge the two into one. Not reused after a delete within one document. |
| `title` | Plain text. Custom-emoji title entities are not synced in version 1. |
| `color` | `-1` for none, otherwise the folder-tag colour index as in `dialogFilter.color`. |
| `flags` | The `dialogFilter` TL flag names with the same meaning, so a reader can build a native `DialogFilter` and reuse its own membership logic. Missing flags are false. |
| `include`, `exclude`, `pinned` | **Bot API marked peer ids**: user `id`, basic group `-id`, channel or supergroup `-1000000000000 - id`. Identical to Android's `dialogId` and Telethon's `utils.get_peer_id`. Secret chats never appear. `pinned` is ordered (first pinned first) and a subset of `include`. |

## Reading it from a bot (Telethon)

```python
msgs = await client.get_messages("me", search="#mercurygram_folders",
                                 filter=types.InputMessagesFilterDocument, limit=5)
msgs = [m for m in msgs if m.file and m.file.name == "mercurygram-folders.json"]
if msgs:
    raw = await client.download_media(max(msgs, key=lambda m: m.id), bytes)
    doc = json.loads(raw)
```

Each folder maps onto `types.DialogFilter(id=..., title=TextWithEntities(text=title, entities=[]),
include_peers=[...], exclude_peers=[...], pinned_peers=[...], contacts=..., ...)`, with peers
built through `utils.resolve_id(marked)` and a zero `access_hash` (only
`utils.get_peer_id()` is ever needed on them for matching).
