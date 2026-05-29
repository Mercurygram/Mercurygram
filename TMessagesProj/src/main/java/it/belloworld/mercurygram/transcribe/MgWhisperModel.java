package it.belloworld.mercurygram.transcribe;

import android.content.Context;
import android.net.Uri;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * [MG] On-device Whisper model: storage, GitHub download, and manual file import.
 *
 * The model is deliberately NOT bundled in the APK — it is fetched on first use
 * or imported by the user. This keeps the main APK slim and reproducible (the
 * model is never part of the build). Download + manual import mirror the
 * Sayboard pattern (free model + manual alternative) so no new F-Droid
 * antifeature is incurred beyond the NonFreeNet the app already carries for
 * talking to Telegram's servers. Download style mirrors
 * {@link it.belloworld.mercurygram.MgUpdateChecker#downloadUpdate}.
 */
public final class MgWhisperModel {

    /** Available model tiers. ids match {@link SharedConfig#mg_transcribeModel}. */
    public enum Model {
        // Canonical multilingual ggml models from huggingface.co/ggerganov/whisper.cpp
        // (MIT). Quant policy: q8_0 for the small models (near-lossless vs f16, often
        // a touch FASTER on CPU since whisper is memory-bound, cheap in size), q5_1
        // only once the model is big enough that q8 costs too much (small). The
        // whisper-models GitHub release MUST host these EXACT bytes or the pinned
        // SHA-256 check rejects the download.
        TINY("tiny-q8_0", "ggml-tiny-q8_0.bin", 43_537_433L,
                "c2085835d3f50733e2ff6e4b41ae8a2b8d8110461e18821b09a15c40c42d1cca"),
        BASE("base-q8_0", "ggml-base-q8_0.bin", 81_768_585L,
                "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9"),
        SMALL("small-q5_1", "ggml-small-q5_1.bin", 190_085_487L,
                "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb");

        public final String id;
        public final String fileName;
        public final long approxSize;
        public final String sha256;

        Model(String id, String fileName, long approxSize, String sha256) {
            this.id = id;
            this.fileName = fileName;
            this.approxSize = approxSize;
            this.sha256 = sha256;
        }

        public static Model byId(String id) {
            for (Model m : values()) {
                if (m.id.equals(id)) {
                    return m;
                }
            }
            return TINY;
        }
    }

    // Models live in their own GitHub release tag so they aren't re-uploaded per app release.
    private static final String DOWNLOAD_BASE =
            "https://github.com/Mercurygram/Mercurygram/releases/download/whisper-models/";

    // Shared Silero VAD model (one for all tiers). Strips silence/non-speech
    // before decoding so the tiny model can't hallucinate on silent/short clips.
    // Downloaded alongside the speech model (piggyback), never bundled.
    public static final String VAD_FILE = "ggml-silero-v6.2.0.bin";
    private static final long VAD_SIZE = 885_098L;
    private static final String VAD_SHA256 =
            "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987";

    private static final AtomicBoolean isDownloading = new AtomicBoolean(false);
    // Separate guard: the small VAD model downloads independently (piggyback /
    // lazy) and must not contend with the user-initiated speech-model download.
    private static final AtomicBoolean isVadDownloading = new AtomicBoolean(false);
    private static volatile HttpURLConnection currentConn;

    // Cache for isSelectedInstalled(): the selected model file only changes via
    // download / import / delete (which bump installEpoch), and selecting a
    // different tier changes the cache key. Keeps the per-voice-cell-bind
    // isUsable() probe off the filesystem after the first hit, mirroring the
    // deviceSupported() cache in MgWhisperTranscriber.
    private static volatile int installEpoch = 0;
    private static volatile int installedCacheEpoch = -1;
    private static volatile String installedCacheModelId = null;
    private static volatile boolean installedCacheValue = false;

    private MgWhisperModel() {
    }

    public interface ProgressCallback {
        void onProgress(long done, long total);

        void onComplete(File file);

        void onError(String message);
    }

    /** Currently selected model per config. */
    public static Model selected() {
        return Model.byId(SharedConfig.mg_transcribeModel);
    }

    private static File modelDir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "whisper");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File fileFor(Model model) {
        return new File(modelDir(), model.fileName);
    }

    /** True if the given model file exists and is plausibly complete. */
    public static boolean isInstalled(Model model) {
        File f = fileFor(model);
        return f.exists() && f.length() > 1_000_000L;
    }

    /**
     * True if the currently selected model is installed. Cached: this is on the
     * UI hot path (every voice/round-video cell bind, via
     * {@link MgWhisperTranscriber#isUsable()}), so it must not stat the
     * filesystem on every call. Invalidated by {@link #installEpoch} bumps on
     * download / import / delete; a model-tier change misses on the id key.
     */
    public static boolean isSelectedInstalled() {
        Model m = selected();
        int epoch = installEpoch;
        if (epoch == installedCacheEpoch && m.id.equals(installedCacheModelId)) {
            return installedCacheValue;
        }
        boolean v = isInstalled(m);
        installedCacheValue = v;
        installedCacheModelId = m.id;
        installedCacheEpoch = epoch; // publish last
        return v;
    }

    public static String absolutePathFor(Model model) {
        return fileFor(model).getAbsolutePath();
    }

    public static File vadFile() {
        return new File(modelDir(), VAD_FILE);
    }

    public static String vadAbsolutePath() {
        return vadFile().getAbsolutePath();
    }

    /** True if the shared Silero VAD model is present and plausibly complete (~885 kB). */
    public static boolean isVadInstalled() {
        File f = vadFile();
        return f.exists() && f.length() > 100_000L;
    }

    public static boolean isDownloading() {
        return isDownloading.get();
    }

    public static void cancelDownload() {
        isDownloading.set(false);
        HttpURLConnection c = currentConn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * Downloads {@code model} from the GitHub release into the model dir.
     * Callbacks are delivered on the UI thread. On success, also fetches the
     * shared Silero VAD model in the background if it isn't present yet
     * (piggyback) so on-by-default VAD works without a separate user action.
     */
    public static void download(Model model, ProgressCallback callback) {
        downloadBlob(model.fileName, model.approxSize, model.sha256, isDownloading, true, callback);
    }

    /** Downloads the shared Silero VAD model into the model dir. */
    public static void downloadVad(ProgressCallback callback) {
        downloadBlob(VAD_FILE, VAD_SIZE, VAD_SHA256, isVadDownloading, false, callback);
    }

    /** Fire-and-forget VAD fetch if it isn't present (piggyback / lazy). No-op if present or in flight. */
    public static void ensureVadDownloaded() {
        if (isVadInstalled() || isVadDownloading.get()) {
            return;
        }
        downloadVad(new ProgressCallback() {
            @Override public void onProgress(long done, long total) {}
            @Override public void onComplete(File file) {}
            @Override public void onError(String message) {}
        });
    }

    /**
     * Streams {@code fileName} from the GitHub release into the model dir,
     * verifying its SHA-256 inline, then atomically renaming the {@code .part}
     * into place. Used for both speech models and the VAD model — {@code busy}
     * is the per-kind in-flight guard so the small VAD download never contends
     * with the user-initiated speech download. When {@code piggybackVad} is
     * true, a successful download also triggers {@link #ensureVadDownloaded()}.
     */
    private static void downloadBlob(String fileName, long approxSize, String sha256,
                                     AtomicBoolean busy, boolean piggybackVad, ProgressCallback callback) {
        if (!busy.compareAndSet(false, true)) {
            AndroidUtilities.runOnUIThread(() -> callback.onError("Already downloading"));
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection conn = null;
            File tmp = null;
            try {
                File target = new File(modelDir(), fileName);
                tmp = new File(target.getAbsolutePath() + ".part");

                conn = (HttpURLConnection) new URL(DOWNLOAD_BASE + fileName).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);
                currentConn = conn;

                int code = conn.getResponseCode();
                if (code != 200) {
                    AndroidUtilities.runOnUIThread(() -> callback.onError("HTTP " + code));
                    return;
                }
                long total = conn.getContentLength();
                if (total <= 0) {
                    total = approxSize;
                }
                final long totalFinal = total;

                MessageDigest digest = null;
                try {
                    digest = MessageDigest.getInstance("SHA-256");
                } catch (Exception ignore) {
                }
                try (InputStream is = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    long done = 0;
                    int len;
                    int lastPct = -1;
                    while ((len = is.read(buf)) != -1) {
                        if (!busy.get()) {
                            throw new IOException("cancelled");
                        }
                        fos.write(buf, 0, len);
                        if (digest != null) {
                            digest.update(buf, 0, len);
                        }
                        done += len;
                        // Post progress only when the integer percent changes — a
                        // 64 KB buffer over a ~190 MB blob would otherwise flood the
                        // main looper with ~2900 runnables.
                        int pct = totalFinal > 0 ? (int) (done * 100 / totalFinal) : -1;
                        if (pct != lastPct) {
                            lastPct = pct;
                            final long d = done;
                            AndroidUtilities.runOnUIThread(() -> callback.onProgress(d, totalFinal));
                        }
                    }
                }

                // Hash is computed inline during the stream above — no second
                // full-file read of a possibly-190 MB blob.
                if (!matchesHash(digest, sha256)) {
                    tmp.delete();
                    AndroidUtilities.runOnUIThread(() -> callback.onError("Checksum mismatch"));
                    return;
                }
                if (target.exists()) {
                    target.delete();
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete();
                    AndroidUtilities.runOnUIThread(() -> callback.onError("Could not save model"));
                    return;
                }
                installEpoch++; // model file changed → invalidate isSelectedInstalled() cache
                final File done = target;
                AndroidUtilities.runOnUIThread(() -> callback.onComplete(done));
                if (piggybackVad) {
                    ensureVadDownloaded();
                }
            } catch (Exception e) {
                boolean cancelled = !busy.get();
                if (tmp != null && tmp.exists()) {
                    tmp.delete();
                }
                if (!cancelled) {
                    FileLog.e(e);
                }
                final String msg = cancelled ? "cancelled" : e.getMessage();
                AndroidUtilities.runOnUIThread(() -> callback.onError(msg));
            } finally {
                busy.set(false);
                currentConn = null;
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /**
     * Imports a user-picked model file (SAF {@code content://} URI) into the model
     * dir for {@code model}. Runs off the main thread; callbacks on the UI thread.
     */
    public static void importFromUri(Context context, Model model, Uri uri, ProgressCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            File target = fileFor(model);
            File tmp = new File(target.getAbsolutePath() + ".part");
            try (InputStream is = context.getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                if (is == null) {
                    throw new IOException("cannot open file");
                }
                byte[] buf = new byte[1 << 16];
                long done = 0;
                int len;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                    done += len;
                    final long d = done;
                    AndroidUtilities.runOnUIThread(() -> callback.onProgress(d, model.approxSize));
                }
            } catch (Exception e) {
                FileLog.e(e);
                if (tmp.exists()) {
                    tmp.delete();
                }
                final String msg = e.getMessage();
                AndroidUtilities.runOnUIThread(() -> callback.onError(msg));
                return;
            }
            // Manual import skips the pinned-hash check (the user vouches for the file),
            // but reject a too-small file that obviously isn't a model.
            if (tmp.length() < 1_000_000L) {
                tmp.delete();
                AndroidUtilities.runOnUIThread(() -> callback.onError("File too small to be a model"));
                return;
            }
            if (target.exists()) {
                target.delete();
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                AndroidUtilities.runOnUIThread(() -> callback.onError("Could not save model"));
                return;
            }
            installEpoch++; // model file changed → invalidate isSelectedInstalled() cache
            AndroidUtilities.runOnUIThread(() -> callback.onComplete(target));
        });
    }

    public static void delete(Model model) {
        File f = fileFor(model);
        if (f.exists()) {
            f.delete();
        }
        installEpoch++; // invalidate isSelectedInstalled() cache
        // Drop the in-memory engine ctx so a deleted model can't keep serving
        // (and isn't leaked as ~40-190 MB of native heap for the process life).
        MgWhisperTranscriber.releaseEngine();
    }

    /**
     * Compares a digest accumulated during download against the pinned hash.
     * Empty {@code expected} means "not pinned yet" → accept with a warning.
     */
    private static boolean matchesHash(MessageDigest digest, String expected) {
        if (expected == null || expected.isEmpty()) {
            FileLog.w("MgWhisperModel: no pinned SHA-256 — accepting unverified");
            return true;
        }
        if (digest == null) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString().equalsIgnoreCase(expected);
    }
}
