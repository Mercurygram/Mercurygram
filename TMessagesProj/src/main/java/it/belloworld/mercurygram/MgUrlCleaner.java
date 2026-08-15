package it.belloworld.mercurygram;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.widget.EditText;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
 *
 * The rules are hand-written rather than downloaded or vendored on purpose: a
 * privacy toggle that phones home for its rule list defeats itself, and the
 * published lists (ClearURLs, AdGuard) are mostly domain-scoped, so flattening
 * them into one global set would import their false positives too.
 */
public class MgUrlCleaner {

    /**
     * A parameter name set. An entry ending in {@code _} matches by prefix
     * ({@code utm_} covers {@code utm_source}), anything else matches exactly.
     */
    private static final class Rules {
        private final Set<String> exact = new HashSet<>();
        private final ArrayList<String> prefixes = new ArrayList<>();

        Rules(String... names) {
            for (String name : names) {
                if (name.endsWith("_")) {
                    prefixes.add(name);
                } else {
                    exact.add(name);
                }
            }
        }

        boolean matches(String name) {
            if (exact.contains(name)) {
                return true;
            }
            for (int i = 0; i < prefixes.size(); i++) {
                if (name.startsWith(prefixes.get(i))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Ad-network and campaign parameters that carry no meaning on any site. */
    private static final Rules GLOBAL = new Rules(
            // campaign tagging schemes
            "utm_", "pk_", "mtm_", "piwik_", "matomo_", "hsa_", "vero_", "oly_",
            // Google Ads
            "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "gad_source", "gad_campaignid", "srsltid",
            // Meta. Instagram has shipped three different share ids over time.
            "fbclid", "fb_action_ids", "fb_action_types", "fb_source", "fb_ref",
            "igshid", "igsh", "igsi",
            // other ad networks
            "msclkid", "twclid", "ttclid", "yclid", "ysclid", "_openstat",
            "epik", "rdt_cid", "li_fat_id", "sc_cid", "ScCid", "erid", "spm", "scm",
            // affiliate networks
            "irclickid", "irgwc", "cjevent", "awc", "sscid", "clickid", "affid",
            "ranMID", "ranEAID", "ranSiteID",
            // email and marketing automation
            "mc_cid", "mc_eid", "mkt_tok", "_hsenc", "_hsmi", "__hssc", "__hstc", "__hsfp",
            "hsCtaTracking", "__s", "_kx", "wickedid", "s_kwcid", "s_cid", "ef_id",
            // analytics leftovers
            "_ga", "_gl", "_gid", "_branch_match_id", "_branch_referrer",
            "adjust_tracker", "af_siteid",
            // publisher-internal campaign ids
            "ncid", "cmpid", "icid", "intcid", "int_source", "ito", "ftag", "CMP"
    );

    /**
     * Parameters that are tracking only on their own site. {@code si} is the
     * reason this map exists: YouTube and Spotify use it as a share id, plenty
     * of unrelated sites use it as a real parameter, so stripping it everywhere
     * silently corrupted links.
     *
     * A key containing a dot matches that host and its subdomains. A key with
     * no dot matches the second-level label, for sites spread over many TLDs
     * (amazon.co.uk, google.de). The latter also matches a third-party host
     * carrying that label, which is acceptable for a rule that only drops
     * query parameters.
     */
    private static final Map<String, Rules> HOST_RULES = new LinkedHashMap<>();

    private static void host(Rules rules, String... hosts) {
        for (String h : hosts) {
            HOST_RULES.put(h, rules);
        }
    }

    static {
        host(new Rules("si", "pp", "feature"), "youtube.com", "youtu.be");
        host(new Rules("si", "nd", "context"), "spotify.com", "spotify.link");
        host(new Rules("s", "t", "cxt", "ref_src", "ref_url"), "twitter.com", "x.com");
        host(new Rules("_r", "_t", "is_from_webapp", "sender_device", "sender_web_id",
                "share_app_id", "share_link_id", "share_item_id", "tt_from", "u_code",
                "refer", "is_copy_url", "checksum"), "tiktok.com");
        host(new Rules("__tn__", "__cft__", "mibextid", "rdid", "refsrc", "hc_ref", "_rdr"),
                "facebook.com", "fb.watch");
        // img_index picks which image of a carousel opens, so it stays
        host(new Rules("stkn"), "instagram.com");
        host(new Rules("share_id", "correlation_id", "ref", "ref_source", "rdt", "chainedPosts"),
                "reddit.com", "redd.it");
        // th and psc select a product variant, so they stay.
        host(new Rules("tag", "ref", "ref_", "pd_rd_", "pf_rd_", "qid", "sr", "_encoding",
                "linkCode", "creative", "creativeASIN", "ascsubtag", "dib", "dib_tag",
                "content-id", "sp_csd"), "amazon");
        host(new Rules("aff_", "algo_pvid", "algo_expid", "btsid", "ws_ab_test", "pdp_npi",
                "curPageLogUid", "gatewayAdapt", "terminal_id"), "aliexpress.com");
        host(new Rules("trk", "trkInfo", "midToken", "midSig", "eBP", "refId",
                "originalSubdomain"), "linkedin.com");
        host(new Rules("ved", "ei", "oq", "gs_lcp", "gs_lcrp", "gs_lp", "sca_esv", "sclient",
                "sourceid", "usg", "uact", "cad", "aqs"), "google");
        host(new Rules("guccounter", "guce_referrer", "guce_referrer_sig"), "yahoo.com");
        host(new Rules("spm_id_from", "vd_source", "from_source", "share_source", "share_medium",
                "share_plat", "share_session_id", "unique_k", "buvid", "from_spmid"),
                "bilibili.com", "b23.tv");
        host(new Rules("_trkparms", "_trksid", "_from", "mkcid", "mkrid", "campid", "toolid",
                "customid", "mkevt"), "ebay");
        host(new Rules("click_key", "click_sum", "ref", "frs", "organic_search_click"), "etsy.com");
        host(new Rules("source", "sk"), "medium.com");
        host(new Rules("at_"), "bbc.com", "bbc.co.uk");
        host(new Rules("smid", "smtyp"), "nytimes.com");
        host(new Rules("snr"), "steampowered.com", "steamcommunity.com");
        host(new Rules("tt_content", "tt_medium"), "twitch.tv");
    }

    private static boolean enabled() {
        return UserConfig.getInstance(UserConfig.selectedAccount).mg.stripTrackingParams;
    }

    private static Rules rulesFor(String host) {
        if (host == null) {
            return null;
        }
        final String dotted = "." + host.toLowerCase();
        for (Map.Entry<String, Rules> entry : HOST_RULES.entrySet()) {
            final String key = entry.getKey();
            final boolean hit = key.indexOf('.') < 0
                    ? dotted.contains("." + key + ".")
                    : dotted.endsWith("." + key);
            if (hit) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isTracking(String name, Rules hostRules) {
        if (name == null) {
            return false;
        }
        return GLOBAL.matches(name) || (hostRules != null && hostRules.matches(name));
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
     * Cleans every http(s) link in {@code text}, returning the very same
     * instance when there was nothing to strip. For the share-sheet path, where
     * the text is not editable in place.
     */
    public static CharSequence cleanText(CharSequence text) {
        if (TextUtils.isEmpty(text) || !enabled()) {
            return text;
        }
        final SpannableStringBuilder cleaned = new SpannableStringBuilder(text);
        return stripTrackingIn(cleaned) ? cleaned : text;
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

    /**
     * Copies {@code text} with the tracking parameters removed. Returns false
     * when there is nothing to clean, so the caller falls through to its own
     * clipboard handling.
     *
     * The clip is plain text on purpose: a styled clip carries every link a
     * second time inside the href of its HTML flavour, which cleaning the text
     * does not touch, so keeping it would paste a still-tracked hyperlink into
     * an HTML-aware target. Only text that actually carried tracking loses its
     * formatting, and only while the toggle is on.
     */
    public static boolean handleCopy(CharSequence text) {
        if (TextUtils.isEmpty(text) || !enabled()) {
            return false;
        }
        try {
            final CharSequence cleaned = cleanText(text);
            if (cleaned == text) {
                return false;
            }
            final ClipboardManager clipboard = (ClipboardManager) ApplicationLoader.applicationContext
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("label", cleaned.toString()));
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
        final Rules hostRules = rulesFor(uri.getHost());
        // Filter the raw encoded query: decoding the values and re-appending them would
        // rewrite '+' as %2B, turning "?q=hello+world" into a literal plus for the server.
        final StringBuilder kept = new StringBuilder(query.length());
        boolean anyTracking = false;
        for (String pair : query.split("&")) {
            final int eq = pair.indexOf('=');
            if (isTracking(Uri.decode(eq < 0 ? pair : pair.substring(0, eq)), hostRules)) {
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
