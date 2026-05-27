package it.belloworld.mercurygram.translate;

import android.net.Uri;
import android.util.Log;

import com.google.common.base.Charsets;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.TranslateAlert2;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mozhi privacy-proxy HTTP worker for the "alternative" translation path:
 * instance rotation with a per-instance ban window, pinned/custom instance
 * support, and rate-limit reporting. Extracted from TranslateAlert2.
 */
public final class MgMozhiClient {

    private MgMozhiClient() {}

    private static final ConcurrentHashMap<String, Long> mgAltInstanceBanUntilMs = new ConcurrentHashMap<>();
    private static final AtomicInteger mgAltPreferredInstanceIdx = new AtomicInteger(0);
    private static final long MG_ALT_INSTANCE_BAN_WINDOW_MS = 60_000L;
    private static final int MG_ALT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int MG_ALT_READ_TIMEOUT_MS = 15_000;

    /**
     * Source characters per request. The text travels in a POST body, so the
     * limit is the backend engine's own, not the URL length: DuckDuckGo answers
     * 500 above roughly 1000 source characters, Google via Mozhi copes with
     * about 3000. 900 stays under the lowest of them for every script.
     */
    static final int MG_ALT_MAX_CHARS_PER_REQUEST = 900;

    /**
     * Display name of an alternative-HTTP engine id. Shared by the translation
     * settings screen and the "powered by" credit in the chat translate bar.
     */
    public static String engineLabel(String engine) {
        if (engine == null) engine = SharedConfig.MG_TRANSLATE_ALT_ENGINE_DUCKDUCKGO;
        switch (engine) {
            case SharedConfig.MG_TRANSLATE_ALT_ENGINE_GOOGLE:
                return LocaleController.getString(R.string.MercurygramTranslationAlternativeEngineGoogle);
            case SharedConfig.MG_TRANSLATE_ALT_ENGINE_YANDEX:
                return LocaleController.getString(R.string.MercurygramTranslationAlternativeEngineYandex);
            case SharedConfig.MG_TRANSLATE_ALT_ENGINE_DUCKDUCKGO:
            default:
                return LocaleController.getString(R.string.MercurygramTranslationAlternativeEngineDuckDuckGo);
        }
    }

    public static void clearInstanceBans() {
        mgAltInstanceBanUntilMs.clear();
        mgAltPreferredInstanceIdx.set(0);
    }

    /**
     * Translate raw (unencoded) text. Long text is split into chunks of at most
     * {@link #MG_ALT_MAX_CHARS_PER_REQUEST} characters, each sent as its own
     * request on this worker thread and joined back in order; one failed chunk
     * fails the whole call.
     */
    public static void translate(String text, String fromLng, String toLng, Utilities.Callback2<String, Boolean> done) {
        if (done == null) return;
        new Thread() {
            @Override
            public void run() {
                final List<String> instances = SharedConfig.getMgTranslateAltActiveInstances();
                if (instances.isEmpty()) {
                    AndroidUtilities.runOnUIThread(() -> done.run(null, false));
                    return;
                }
                final String engine = SharedConfig.mg_translateAltEngine == null
                        ? SharedConfig.MG_TRANSLATE_ALT_ENGINE_DUCKDUCKGO
                        : SharedConfig.mg_translateAltEngine;
                // "und" is TranslateController.UNKNOWN_LANGUAGE, which the
                // long-press Translate alert passes verbatim when the message
                // language was never detected. Mozhi answers 500 "Source
                // language code invalid" for it, and that bans the instance
                // below, so a single such call burns the whole mirror pool for
                // the ban window. Autodetect instead.
                final String fromCode = (fromLng == null || fromLng.isEmpty()
                        || TranslateController.UNKNOWN_LANGUAGE.equals(fromLng)) ? "auto" : fromLng;
                final String toCode = toLng == null ? "" : toLng;

                final StringBuilder joined = new StringBuilder();
                for (String part : chunk(text, MG_ALT_MAX_CHARS_PER_REQUEST)) {
                    final Attempt attempt = requestChunk(instances, engine, fromCode, toCode, part);
                    if (attempt.text == null) {
                        AndroidUtilities.runOnUIThread(() -> done.run(null, attempt.rateLimit));
                        return;
                    }
                    joined.append(attempt.text);
                }
                final String result = joined.toString();
                AndroidUtilities.runOnUIThread(() -> done.run(result, false));
            }
        }.start();
    }

    /**
     * Split text at at most {@code maxChars} characters per part, preferring the
     * last newline in the window, else the last space, else a hard cut. Parts
     * concatenate back to the input unchanged.
     */
    static List<String> chunk(String text, int maxChars) {
        final ArrayList<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return parts;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int split = text.lastIndexOf('\n', end - 1);
                if (split < start) {
                    split = text.lastIndexOf(' ', end - 1);
                }
                if (split >= start) {
                    end = split + 1;
                }
            }
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }

    /** Outcome of one chunk: translated text, or null plus the rate-limit flag. */
    private static final class Attempt {
        final String text;
        final boolean rateLimit;

        Attempt(String text, boolean rateLimit) {
            this.text = text;
            this.rateLimit = rateLimit;
        }
    }

    private static Attempt requestChunk(List<String> instances, String engine, String fromCode, String toCode, String text) {
        final int total = instances.size();
        final int startIdx = total > 1
                ? ((mgAltPreferredInstanceIdx.get() % total) + total) % total
                : 0;
        boolean sawAttempt = false;
        boolean allRateLimited = true;
        Exception lastError = null;
        int lastResponseCode = -1;

        for (int offset = 0; offset < total; ++offset) {
            final int idx = (startIdx + offset) % total;
            final String instance = stripTrailingSlash(instances.get(idx));
            if (instance == null || instance.isEmpty()) {
                continue;
            }
            final Long banUntil = mgAltInstanceBanUntilMs.get(instance);
            if (banUntil != null && banUntil > System.currentTimeMillis()) {
                // Skip ban-window'd instance without flipping allRateLimited;
                // the ban itself records the original failure reason.
                continue;
            }
            sawAttempt = true;
            HttpURLConnection connection = null;
            try {
                // POST, not GET: the text of a long message in a query string
                // overflows the mirrors' request-line limit (they answer 414 or
                // 431 past ~4 KB, and non-Latin scripts reach that in a few
                // hundred characters once percent-encoded). A body also keeps
                // message text out of the mirrors' access logs.
                final String body = "engine=" + Uri.encode(engine)
                        + "&from=" + Uri.encode(fromCode)
                        + "&to=" + Uri.encode(toCode)
                        + "&text=" + Uri.encode(text);
                connection = (HttpURLConnection) new URI(instance + "/api/translate").toURL().openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setConnectTimeout(MG_ALT_CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(MG_ALT_READ_TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", TranslateAlert2.userAgents[(int) Math.round(Math.random() * (TranslateAlert2.userAgents.length - 1))]);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(Charsets.UTF_8));
                }

                final int code = connection.getResponseCode();
                lastResponseCode = code;
                if (code == 429) {
                    mgAltInstanceBanUntilMs.put(instance, System.currentTimeMillis() + MG_ALT_INSTANCE_BAN_WINDOW_MS);
                    continue;
                }
                if (code < 200 || code >= 300) {
                    allRateLimited = false;
                    // A request-shaped rejection says nothing about the instance
                    // and repeats identically everywhere, so banning on it would
                    // take the whole pool down for unrelated messages.
                    if (!isRequestShaped(code)) {
                        mgAltInstanceBanUntilMs.put(instance, System.currentTimeMillis() + MG_ALT_INSTANCE_BAN_WINDOW_MS);
                    }
                    continue;
                }

                final StringBuilder buf = new StringBuilder();
                try (Reader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), Charsets.UTF_8))) {
                    int c;
                    while ((c = reader.read()) != -1) {
                        buf.append((char) c);
                    }
                }
                final Object parsed = new JSONTokener(buf.toString()).nextValue();
                if (!(parsed instanceof JSONObject)) {
                    allRateLimited = false;
                    mgAltInstanceBanUntilMs.put(instance, System.currentTimeMillis() + MG_ALT_INSTANCE_BAN_WINDOW_MS);
                    continue;
                }
                final JSONObject obj = (JSONObject) parsed;
                // Primary key: official aryak/mozhi. Fallback: forks
                // that renamed it. Treat empty string as failure too.
                String translated = obj.optString("translated-text", "");
                if (translated.isEmpty()) {
                    translated = obj.optString("translation", "");
                }
                if (translated.isEmpty()) {
                    allRateLimited = false;
                    mgAltInstanceBanUntilMs.put(instance, System.currentTimeMillis() + MG_ALT_INSTANCE_BAN_WINDOW_MS);
                    continue;
                }
                // Preserve a leading newline the engine ate, so joining the
                // chunks back together keeps multi-paragraph layout.
                if (text.length() > 0 && text.charAt(0) == '\n' && translated.charAt(0) != '\n') {
                    translated = "\n" + translated;
                }
                mgAltPreferredInstanceIdx.set(idx);
                return new Attempt(translated, false);
            } catch (Exception e) {
                lastError = e;
                allRateLimited = false;
                mgAltInstanceBanUntilMs.put(instance, System.currentTimeMillis() + MG_ALT_INSTANCE_BAN_WINDOW_MS);
            } finally {
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
            }
        }
        if (lastError != null) {
            Log.e("translate", "alternative translation failed across all instances; last code=" + lastResponseCode + " err=" + lastError);
        } else {
            Log.e("translate", "alternative translation failed across all instances; last code=" + lastResponseCode);
        }
        return new Attempt(null, sawAttempt && allRateLimited);
    }

    /** Statuses caused by the request itself rather than by the instance. */
    private static boolean isRequestShaped(int code) {
        return code == 400 || code == 413 || code == 414 || code == 431;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return null;
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
