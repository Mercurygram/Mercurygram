package it.belloworld.mercurygram.translate;

import androidx.annotation.Nullable;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * Keeps links clickable across a Mercurygram translation round trip.
 *
 * <p>Both MG engines (offline AIDL, Mozhi HTTP) take a plain string and give a
 * plain string back, so every entity of the source message used to be dropped.
 * For formatting entities (bold, italic, code) that is only cosmetic, but a
 * dropped {@code textUrl} / {@code url} entity makes the link dead: a sent
 * channel message is rendered from the server entity list and never re-parsed,
 * so an empty list means no tappable link at all.
 *
 * <p>So the link-carrying runs are replaced by {@code {{N}}} sentinels before
 * the text reaches the engine and put back afterwards, with the entity offsets
 * recomputed against the translated text. One message still costs one engine
 * call (Mozhi is one HTTP GET per string, so translating each run separately
 * would multiply the requests and trip the instance rate limit).
 *
 * <p>Consequence: the label of a hidden text link stays in the source language.
 * For urls, mentions and hashtags that is the wanted behaviour anyway; for a
 * hidden text link an untranslated label beats a dead link.
 *
 * <p>No Android or network dependency on purpose, so the logic is unit-testable.
 */
public final class MgTranslateEntities {

    /**
     * Deliberate ceiling: 32 protected runs per message. Above that the message
     * is sent unprotected (links die, exactly as before the sentinel round trip)
     * instead of handing the engine a string that is mostly markers. Raise it
     * only if a real message shows up that needs more.
     */
    private static final int MAX_RUNS = 32;

    private MgTranslateEntities() {}

    /** Source text with its link runs replaced by sentinels, plus what it takes to undo that. */
    public static final class Protected {
        /** What to send to the engine. */
        public final String text;
        /**
         * True when no letter is left, i.e. a link-only message. Translating
         * that is pointless and an engine answers it with a
         * language-not-detected failure, so the caller should keep the source
         * text and its entities instead.
         */
        public final boolean nothingToTranslate;

        private final TLRPC.TL_textWithEntities src;
        private final ArrayList<TLRPC.MessageEntity> runs;

        private Protected(TLRPC.TL_textWithEntities src, String text, ArrayList<TLRPC.MessageEntity> runs) {
            this.src = src;
            this.text = text;
            this.runs = runs;
            this.nothingToTranslate = !runs.isEmpty() && !hasLetter(text);
        }

        /** Copy of the source, for the {@link #nothingToTranslate} case. */
        public TLRPC.TL_textWithEntities unchanged() {
            final TLRPC.TL_textWithEntities out = new TLRPC.TL_textWithEntities();
            out.text = src == null || src.text == null ? "" : src.text;
            if (src != null && src.entities != null) {
                out.entities = new ArrayList<>(src.entities);
            }
            return out;
        }
    }

    public static Protected protect(@Nullable TLRPC.TL_textWithEntities src) {
        final String text = src == null || src.text == null ? "" : src.text;
        if (src == null || src.entities == null || src.entities.isEmpty()) {
            return new Protected(src, text, new ArrayList<>());
        }

        final ArrayList<TLRPC.MessageEntity> candidates = new ArrayList<>();
        for (int i = 0; i < src.entities.size(); ++i) {
            final TLRPC.MessageEntity entity = src.entities.get(i);
            if (entity != null && isLink(entity) && entity.offset >= 0 && entity.length > 0
                    && entity.offset + entity.length <= text.length()) {
                candidates.add(entity);
            }
        }
        if (candidates.isEmpty()) {
            return new Protected(src, text, new ArrayList<>());
        }
        // Outermost first, so a link nested inside another one is skipped below.
        Collections.sort(candidates, (a, b) -> a.offset != b.offset
                ? Integer.compare(a.offset, b.offset)
                : Integer.compare(b.length, a.length));

        final ArrayList<TLRPC.MessageEntity> runs = new ArrayList<>();
        final StringBuilder sb = new StringBuilder(text.length());
        int pos = 0;
        for (int i = 0; i < candidates.size(); ++i) {
            final TLRPC.MessageEntity entity = candidates.get(i);
            if (entity.offset < pos) {
                continue; // starts inside an already protected run
            }
            if (runs.size() >= MAX_RUNS) {
                return new Protected(src, text, new ArrayList<>());
            }
            sb.append(text, pos, entity.offset).append(marker(runs.size()));
            runs.add(entity);
            pos = entity.offset + entity.length;
        }
        sb.append(text, pos, text.length());
        return new Protected(src, sb.toString(), runs);
    }

    /**
     * Put the protected runs back into {@code translated} and re-anchor their
     * entities. Returns null when the engine ate or duplicated a sentinel; the
     * caller then keeps the plain translated text without entities, so a
     * translator that mangles the markers degrades to the old behaviour instead
     * of leaking {@code {{0}}} into the bubble.
     */
    @Nullable
    public static TLRPC.TL_textWithEntities restore(@Nullable Protected p, @Nullable String translated) {
        if (p == null || translated == null) {
            return null;
        }
        final TLRPC.TL_textWithEntities out = new TLRPC.TL_textWithEntities();
        if (p.runs.isEmpty()) {
            out.text = translated;
            return out;
        }
        // {position in translated, run index}: engines are free to reorder runs.
        final int[][] found = new int[p.runs.size()][2];
        for (int i = 0; i < p.runs.size(); ++i) {
            final String marker = marker(i);
            final int at = translated.indexOf(marker);
            if (at < 0 || translated.indexOf(marker, at + marker.length()) >= 0) {
                return null;
            }
            found[i][0] = at;
            found[i][1] = i;
        }
        Arrays.sort(found, (a, b) -> Integer.compare(a[0], b[0]));

        final StringBuilder sb = new StringBuilder(translated.length());
        int pos = 0;
        for (int[] f : found) {
            sb.append(translated, pos, f[0]);
            final TLRPC.MessageEntity run = p.runs.get(f[1]);
            final String label = p.src.text.substring(run.offset, run.offset + run.length);
            final TLRPC.MessageEntity entity = copy(run);
            // UTF-16 code units, the unit the client stores entity offsets in.
            entity.offset = sb.length();
            entity.length = label.length();
            out.entities.add(entity);
            sb.append(label);
            pos = f[0] + marker(f[1]).length();
        }
        sb.append(translated, pos, translated.length());
        out.text = sb.toString();
        return out;
    }

    private static String marker(int index) {
        return "{{" + index + "}}";
    }

    private static boolean hasLetter(String text) {
        for (int i = 0; i < text.length(); ++i) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Entity types whose text must survive translation for the link to keep working. */
    private static boolean isLink(TLRPC.MessageEntity entity) {
        return entity instanceof TLRPC.TL_messageEntityUrl
                || entity instanceof TLRPC.TL_messageEntityTextUrl
                || entity instanceof TLRPC.TL_messageEntityMention
                || entity instanceof TLRPC.TL_messageEntityMentionName
                || entity instanceof TLRPC.TL_inputMessageEntityMentionName
                || entity instanceof TLRPC.TL_messageEntityHashtag
                || entity instanceof TLRPC.TL_messageEntityCashtag
                || entity instanceof TLRPC.TL_messageEntityEmail
                || entity instanceof TLRPC.TL_messageEntityPhone
                || entity instanceof TLRPC.TL_messageEntityBotCommand
                || entity instanceof TLRPC.TL_messageEntityBankCard;
    }

    /** Copy, not reuse: the source message keeps its own offsets. */
    private static TLRPC.MessageEntity copy(TLRPC.MessageEntity src) {
        final TLRPC.MessageEntity out;
        if (src instanceof TLRPC.TL_messageEntityTextUrl) {
            out = new TLRPC.TL_messageEntityTextUrl();
        } else if (src instanceof TLRPC.TL_messageEntityMentionName) {
            final TLRPC.TL_messageEntityMentionName mention = new TLRPC.TL_messageEntityMentionName();
            mention.user_id = ((TLRPC.TL_messageEntityMentionName) src).user_id;
            out = mention;
        } else if (src instanceof TLRPC.TL_inputMessageEntityMentionName) {
            final TLRPC.TL_inputMessageEntityMentionName mention = new TLRPC.TL_inputMessageEntityMentionName();
            mention.user_id = ((TLRPC.TL_inputMessageEntityMentionName) src).user_id;
            out = mention;
        } else if (src instanceof TLRPC.TL_messageEntityMention) {
            out = new TLRPC.TL_messageEntityMention();
        } else if (src instanceof TLRPC.TL_messageEntityHashtag) {
            out = new TLRPC.TL_messageEntityHashtag();
        } else if (src instanceof TLRPC.TL_messageEntityCashtag) {
            out = new TLRPC.TL_messageEntityCashtag();
        } else if (src instanceof TLRPC.TL_messageEntityEmail) {
            out = new TLRPC.TL_messageEntityEmail();
        } else if (src instanceof TLRPC.TL_messageEntityPhone) {
            out = new TLRPC.TL_messageEntityPhone();
        } else if (src instanceof TLRPC.TL_messageEntityBotCommand) {
            out = new TLRPC.TL_messageEntityBotCommand();
        } else if (src instanceof TLRPC.TL_messageEntityBankCard) {
            out = new TLRPC.TL_messageEntityBankCard();
        } else {
            out = new TLRPC.TL_messageEntityUrl();
        }
        out.flags = src.flags;
        out.url = src.url;
        out.language = src.language;
        return out;
    }
}
