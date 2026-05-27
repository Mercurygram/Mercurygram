package it.belloworld.mercurygram.translate;

import android.net.Uri;
import android.util.Log;

import com.google.common.base.Charsets;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.TranslateAlert2;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
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

    public static void clearInstanceBans() {
        mgAltInstanceBanUntilMs.clear();
        mgAltPreferredInstanceIdx.set(0);
    }

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
                final String fromCode = (fromLng == null || fromLng.isEmpty()) ? "auto" : fromLng;
                final String toCode = toLng == null ? "" : toLng;

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
                        final String uri = instance
                                + "/api/translate?engine=" + Uri.encode(engine)
                                + "&from=" + Uri.encode(fromCode)
                                + "&to=" + Uri.encode(toCode)
                                + "&text=" + text;
                        connection = (HttpURLConnection) new URI(uri).toURL().openConnection();
                        connection.setRequestMethod("GET");
                        connection.setConnectTimeout(MG_ALT_CONNECT_TIMEOUT_MS);
                        connection.setReadTimeout(MG_ALT_READ_TIMEOUT_MS);
                        connection.setRequestProperty("User-Agent", TranslateAlert2.userAgents[(int) Math.round(Math.random() * (TranslateAlert2.userAgents.length - 1))]);
                        connection.setRequestProperty("Accept", "application/json");

                        final int code = connection.getResponseCode();
                        lastResponseCode = code;
                        if (code == 429) {
                            mgAltInstanceBanUntilMs.put(instance, System.currentTimeMillis() + MG_ALT_INSTANCE_BAN_WINDOW_MS);
                            continue;
                        }
                        if (code < 200 || code >= 300) {
                            allRateLimited = false;
                            mgAltInstanceBanUntilMs.put(instance, System.currentTimeMillis() + MG_ALT_INSTANCE_BAN_WINDOW_MS);
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
                        // Preserve leading newline like the prior Google-path
                        // worker did, so the chunk-join in the wrapper keeps
                        // multi-paragraph layout.
                        if (text.length() > 0 && text.charAt(0) == '\n' && translated.charAt(0) != '\n') {
                            translated = "\n" + translated;
                        }
                        final String finalResult = translated;
                        mgAltPreferredInstanceIdx.set(idx);
                        AndroidUtilities.runOnUIThread(() -> done.run(finalResult, false));
                        return;
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
                final boolean rateLimit = sawAttempt && allRateLimited;
                AndroidUtilities.runOnUIThread(() -> done.run(null, rateLimit));
            }
        }.start();
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
