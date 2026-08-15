package it.belloworld.mercurygram;

import android.content.ClipData;
import android.net.Uri;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.widget.EditText;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Strips click-tracking query parameters from links, so opening one does not
 * report back to the campaign that put it there, and forwarding one does not
 * carry the sender's identifier to everybody else.
 *
 * Gated by the per-account {@code stripTrackingParams} toggle; every entry
 * point returns its input unchanged when the toggle is off, so the call sites
 * in upstream files stay one line each.
 */
public class MgUrlCleaner {

    // Exact parameter names. Anything starting with "utm_" is dropped too.
    private static final Set<String> TRACKING_PARAMS = new HashSet<>(Arrays.asList(
            "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid",
            "yclid", "ysclid", "msclkid", "twclid", "ttclid", "igshid", "igsh",
            "mc_cid", "mc_eid", "mkt_tok", "_openstat", "vero_id", "vero_conv",
            "oly_anon_id", "oly_enc_id", "hsCtaTracking", "__s", "wickedid",
            "erid", "spm", "scm", "ref_src", "ref_url", "si", "s_kwcid"
    ));

    private static boolean enabled() {
        return UserConfig.getInstance(UserConfig.selectedAccount).mg.stripTrackingParams;
    }

    private static boolean isTracking(String name) {
        return name != null && (TRACKING_PARAMS.contains(name) || name.startsWith("utm_"));
    }

    public static Uri clean(Uri uri) {
        if (uri == null || !enabled()) {
            return uri;
        }
        return stripTracking(uri);
    }

    /** Cleans every http(s) link in {@code text} in place, keeping its spans. */
    public static void clean(Editable text) {
        if (text == null || !enabled()) {
            return;
        }
        stripTrackingIn(text);
    }

    /**
     * Rewrites every link that carries tracking; returns true when anything changed.
     * Toggle-independent, so it can be exercised directly by tests.
     */
    public static boolean stripTrackingIn(Editable text) {
        if (TextUtils.isEmpty(text) || AndroidUtilities.WEB_URL == null) {
            return false;
        }
        final Matcher matcher = AndroidUtilities.WEB_URL.matcher(text);
        final ArrayList<int[]> ranges = new ArrayList<>();
        final ArrayList<String> replacements = new ArrayList<>();
        while (matcher.find()) {
            final String original = matcher.group();
            final String cleaned = stripTracking(Uri.parse(original)).toString();
            if (!cleaned.equals(original)) {
                ranges.add(new int[]{matcher.start(), matcher.end()});
                replacements.add(cleaned);
            }
        }
        // back to front, so the offsets collected above stay valid
        for (int i = ranges.size() - 1; i >= 0; i--) {
            text.replace(ranges.get(i)[0], ranges.get(i)[1], replacements.get(i));
        }
        return !ranges.isEmpty();
    }

    /**
     * Pastes {@code clip} with the tracking parameters removed. Returns false
     * when there is nothing to clean, so the caller falls through to the stock
     * paste handling.
     */
    public static boolean handlePaste(EditText editText, ClipData clip) {
        if (editText == null || clip == null || clip.getItemCount() < 1 || !enabled()) {
            return false;
        }
        try {
            final CharSequence pasted = clip.getItemAt(0).coerceToText(editText.getContext());
            if (TextUtils.isEmpty(pasted)) {
                return false;
            }
            final SpannableStringBuilder cleaned = new SpannableStringBuilder(pasted);
            if (!stripTrackingIn(cleaned)) {
                return false;
            }
            final int start = Math.max(0, Math.min(editText.getSelectionStart(), editText.getSelectionEnd()));
            final int end = Math.min(editText.getText().length(), Math.max(editText.getSelectionStart(), editText.getSelectionEnd()));
            editText.getText().replace(start, end, cleaned);
            editText.setSelection(Math.min(editText.getText().length(), start + cleaned.length()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The toggle-independent part, so it can be exercised directly by tests. */
    public static Uri stripTracking(Uri uri) {
        final String scheme = uri.getScheme();
        if (uri.isOpaque() || scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return uri;
        }
        final String query = uri.getEncodedQuery();
        if (TextUtils.isEmpty(query)) {
            return uri;
        }
        // Filter the raw encoded query: decoding the values and re-appending them would
        // rewrite '+' as %2B, turning "?q=hello+world" into a literal plus for the server.
        final StringBuilder kept = new StringBuilder(query.length());
        boolean anyTracking = false;
        for (String pair : query.split("&")) {
            final int eq = pair.indexOf('=');
            if (isTracking(Uri.decode(eq < 0 ? pair : pair.substring(0, eq)))) {
                anyTracking = true;
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(pair);
        }
        // Nothing to remove: pass the link through byte-identical.
        if (!anyTracking) {
            return uri;
        }
        return uri.buildUpon().encodedQuery(kept.length() == 0 ? null : kept.toString()).build();
    }
}
