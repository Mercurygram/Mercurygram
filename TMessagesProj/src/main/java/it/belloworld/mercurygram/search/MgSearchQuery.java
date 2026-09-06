package it.belloworld.mercurygram.search;

import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Mercurygram: typed operators for the in-chat search field (issue #134).
 *
 * The query "from:alice type:photo after:2026-01-01 cake" is split into the plain text "cake"
 * plus the pieces of {@code messages.search} the in-chat path never fills in: {@code from_id},
 * {@code filter}, {@code min_date}/{@code max_date}. Only operators the server can evaluate are
 * supported - boolean/size/numeric filters have no TL counterpart and no local text index exists
 * to fake them client-side.
 *
 * Grammar (whitespace-separated {@code key:value} tokens, keys and keyword values
 * case-insensitive, no quoting - usernames cannot contain spaces):
 *
 * <ul>
 * <li>{@code from:username} or {@code from:@username}</li>
 * <li>{@code before:D} / {@code after:D} / {@code date:D} with {@code D} one of
 *     {@code YYYY-MM-DD}, {@code today}, {@code yesterday}; device-local midnight, Gmail
 *     semantics ({@code after} includes the day, {@code before} excludes it, {@code date} is the
 *     one-day range)</li>
 * <li>{@code type:X} with X one of photo, video, voice, round, music, gif, file/doc/document,
 *     link/url, contact, geo/location, poll, mention, pinned - one filter per query, last
 *     wins</li>
 * </ul>
 *
 * A token that does not parse stays in the text verbatim, so a literal "from:x" can still be
 * searched for - including a {@code from:} whose username is not known locally, which would
 * otherwise silently widen the search to every sender. Hashtag/cashtag queries pass through
 * untouched to keep the upstream hashtag search path byte-identical.
 */
public class MgSearchQuery {

    /** Neutral instance for "no search parsed yet", so call sites need no null check. */
    public static final MgSearchQuery EMPTY = new MgSearchQuery(null);

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,32}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Remaining free text after the operator tokens are stripped. */
    public String q;
    public TLRPC.MessagesFilter filter;
    public int minDate;
    public int maxDate;
    public String fromUsername;
    public TLRPC.InputPeer fromPeer;

    private MgSearchQuery(String q) {
        this.q = q;
    }

    public static MgSearchQuery parse(String raw) {
        return parse(raw, null);
    }

    /**
     * @param messagesController resolves {@code from:} against the local user/chat cache; when null
     *                           the username is parsed but left unresolved (tests, no session).
     */
    public static MgSearchQuery parse(String raw, MessagesController messagesController) {
        MgSearchQuery result = new MgSearchQuery(raw);
        if (raw == null) {
            return result;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("#") || trimmed.startsWith("$")) {
            return result;
        }
        StringBuilder rest = new StringBuilder();
        for (String token : WHITESPACE.split(trimmed)) {
            if (!parseToken(result, token, messagesController)) {
                if (rest.length() > 0) {
                    rest.append(' ');
                }
                rest.append(token);
            }
        }
        result.q = rest.toString();
        return result;
    }

    private static boolean parseToken(MgSearchQuery result, String token, MessagesController messagesController) {
        int colon = token.indexOf(':');
        if (colon <= 0 || colon == token.length() - 1) {
            return false;
        }
        String key = token.substring(0, colon).toLowerCase(Locale.US);
        String value = token.substring(colon + 1);
        switch (key) {
            case "from": {
                String username = value.startsWith("@") ? value.substring(1) : value;
                if (!USERNAME.matcher(username).matches()) {
                    return false;
                }
                if (messagesController != null) {
                    TLRPC.InputPeer peer = MessagesController.getInputPeer(messagesController.getUserOrChat(username));
                    if (peer == null) {
                        return false;
                    }
                    result.fromPeer = peer;
                }
                result.fromUsername = username;
                return true;
            }
            case "before":
            case "after":
            case "date": {
                LocalDate day = parseDay(value.toLowerCase(Locale.US));
                if (day == null) {
                    return false;
                }
                int start = startOfDay(day);
                int end = startOfDay(day.plusDays(1));
                if (start <= 0 || end <= 0) {
                    return false;
                }
                if ("after".equals(key)) {
                    result.minDate = start;
                } else if ("before".equals(key)) {
                    result.maxDate = start;
                } else {
                    result.minDate = start;
                    result.maxDate = end;
                }
                return true;
            }
            case "type": {
                TLRPC.MessagesFilter filter = filterFor(value.toLowerCase(Locale.US));
                if (filter == null) {
                    return false;
                }
                result.filter = filter;
                return true;
            }
            default:
                return false;
        }
    }

    /** The given day value, or null when it is not a valid day. */
    private static LocalDate parseDay(String value) {
        switch (value) {
            case "today":
                return LocalDate.now();
            case "yesterday":
                return LocalDate.now().minusDays(1);
            default:
                try {
                    return LocalDate.parse(value);
                } catch (DateTimeParseException e) {
                    return null;
                }
        }
    }

    /** Device-local midnight as unix seconds, or 0 when the day is outside what a TL date holds. */
    private static int startOfDay(LocalDate day) {
        long seconds = day.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        return seconds > 0 && seconds <= Integer.MAX_VALUE ? (int) seconds : 0;
    }

    private static TLRPC.MessagesFilter filterFor(String value) {
        switch (value) {
            case "photo":
                return new TLRPC.TL_inputMessagesFilterPhotos();
            case "video":
                return new TLRPC.TL_inputMessagesFilterVideo();
            case "voice":
                return new TLRPC.TL_inputMessagesFilterVoice();
            case "round":
                return new TLRPC.TL_inputMessagesFilterRoundVideo();
            case "music":
                return new TLRPC.TL_inputMessagesFilterMusic();
            case "gif":
                return new TLRPC.TL_inputMessagesFilterGif();
            case "file":
            case "doc":
            case "document":
                return new TLRPC.TL_inputMessagesFilterDocument();
            case "link":
            case "url":
                return new TLRPC.TL_inputMessagesFilterUrl();
            case "contact":
                return new TLRPC.TL_inputMessagesFilterContacts();
            case "geo":
            case "location":
                return new TLRPC.TL_inputMessagesFilterGeo();
            case "poll":
                return new TLRPC.TL_inputMessagesFilterPoll();
            case "mention":
                return new TLRPC.TL_inputMessagesFilterMyMentions();
            case "pinned":
                return new TLRPC.TL_inputMessagesFilterPinned();
            default:
                return null;
        }
    }

    /** True when at least one operator was recognized, i.e. the search is narrower than its text. */
    public boolean hasOperators() {
        return filter != null || minDate > 0 || maxDate > 0 || fromPeer != null;
    }

    /**
     * Copies the parsed operators onto the request. {@code req.q} is untouched - the caller
     * already searches for the stripped {@link #q}. A sender picked through the search-from-user
     * UI wins over {@code from:}.
     */
    public void applyTo(TLRPC.TL_messages_search req) {
        if (filter != null) {
            req.filter = filter;
        }
        if (minDate > 0) {
            req.min_date = minDate;
        }
        if (maxDate > 0) {
            req.max_date = maxDate;
        }
        if (fromPeer != null && (req.flags & 1) == 0) {
            req.from_id = fromPeer;
            req.flags |= 1;
        }
    }
}
