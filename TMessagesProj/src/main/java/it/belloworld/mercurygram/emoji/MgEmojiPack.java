package it.belloworld.mercurygram.emoji;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Mercurygram: user-supplied custom emoji pack.
 *
 * Mercurygram ships the Noto emoji set because the Apple set is proprietary and
 * can't be bundled in a FOSS / F-Droid build. This lets a user side-load their
 * own emoji images at runtime into app-private storage — nothing proprietary
 * enters the repo or the APK, the build processes no new assets (F-Droid
 * reproducibility untouched), and we never download the pack ourselves (same
 * posture as the manual whisper-model import: the user vouches for their file).
 *
 * Pack format = the Telegram-Android per-glyph PNG layout: files named
 * {@code <page>_<index>.png} (8 pages, ~3600 glyphs, 66x66). The importer
 * matches on the entry <b>basename</b> only, so a plain .zip of loose PNGs, a
 * re-zipped {@code emoji/} folder, AND a raw Telegram {@code .apk} (emoji live
 * at {@code assets/emoji/...} inside it) all work through the same path.
 *
 * Lookup is lenient + per-glyph: a missing glyph falls back to the bundled
 * asset, so a partial pack — or a layer mismatch after an upstream rebase
 * shifts the EmojiData index map — just renders a few built-in glyphs instead
 * of failing or blanking.
 */
public final class MgEmojiPack {

    private MgEmojiPack() {}

    private static final String DIR = "mg_emoji";
    // <page>_<index>.png — basename only; any directory prefix is ignored so a
    // Telegram APK's assets/emoji/0_0.png matches the same as a loose 0_0.png.
    private static final Pattern EMOJI_ENTRY = Pattern.compile("^(\\d+)_(\\d+)\\.png$");
    // Guard a crafted zip from exhausting storage: cap per-file size and count.
    private static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20000;

    public interface ProgressCallback {
        void onProgress(int done);
        void onComplete(int count);
        void onError(String message);
    }

    public static File packDir() {
        return new File(ApplicationLoader.getFilesDirFixed(), DIR);
    }

    // Cached glyph count. installedCount() is read on the UI thread from every
    // settings-list rebuild; the directory only changes on import-complete /
    // remove, so memoize it and recompute lazily (-1) only after those events
    // rather than walking ~3600 files + matching a regex on each repaint.
    private static volatile int cachedCount = -1;

    public static int installedCount() {
        int c = cachedCount;
        if (c >= 0) {
            return c;
        }
        File dir = packDir();
        if (!dir.isDirectory()) {
            return cachedCount = 0;
        }
        File[] files = dir.listFiles((d, name) -> EMOJI_ENTRY.matcher(name).matches());
        return cachedCount = (files == null ? 0 : files.length);
    }

    /**
     * Routed from {@link Emoji#loadEmoji}. When the custom pack is enabled and
     * this glyph exists in it, decode that file; otherwise fall back per-glyph
     * to the bundled asset.
     */
    public static Bitmap loadEmojiBitmap(int page, int page2) {
        if (SharedConfig.mg_useCustomEmojiPack) {
            try {
                File f = new File(packDir(), page + "_" + page2 + ".png");
                if (f.exists()) {
                    // Mirror Emoji.loadBitmap's density-based downsample so a
                    // custom pack renders at the same scale as the bundled set.
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = AndroidUtilities.density <= 1.0f ? 2 : 1;
                    Bitmap bitmap = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                    if (bitmap != null) {
                        return bitmap;
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return Emoji.loadBitmap("emoji/" + String.format(Locale.US, "%d_%d.png", page, page2));
    }

    public static void remove() {
        deleteRecursive(packDir());
        cachedCount = 0;
    }

    /**
     * Extract emoji PNGs from a user-picked .zip/.apk (SAF uri) into the pack
     * dir. Runs on the global queue; callbacks are posted to the UI thread.
     */
    public static void importFromUri(Context context, Uri uri, ProgressCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            File tmp = new File(ApplicationLoader.getFilesDirFixed(), DIR + ".tmp");
            try {
                deleteRecursive(tmp);
                if (!tmp.mkdirs()) {
                    postError(callback, "can't create directory");
                    return;
                }
                int count = 0;
                try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                    if (is == null) {
                        deleteRecursive(tmp);
                        postError(callback, "can't open file");
                        return;
                    }
                    ZipInputStream zis = new ZipInputStream(is);
                    byte[] buf = new byte[16 * 1024];
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (entry.isDirectory()) {
                            continue;
                        }
                        // basename only — never trust the entry's directory path
                        // (zip-slip safe: we build the output from the basename).
                        String base = baseName(entry.getName());
                        Matcher m = EMOJI_ENTRY.matcher(base);
                        if (!m.matches()) {
                            continue;
                        }
                        File out = new File(tmp, base);
                        long written = 0;
                        boolean tooBig = false;
                        try (OutputStream os = new FileOutputStream(out)) {
                            int n;
                            while ((n = zis.read(buf)) != -1) {
                                written += n;
                                if (written > MAX_ENTRY_BYTES) {
                                    tooBig = true;
                                    break;
                                }
                                os.write(buf, 0, n);
                            }
                        }
                        if (tooBig) {
                            out.delete();
                            continue;
                        }
                        count++;
                        if ((count % 200) == 0) {
                            postProgress(callback, count);
                        }
                        if (count >= MAX_ENTRIES) {
                            break;
                        }
                    }
                }
                if (count == 0) {
                    deleteRecursive(tmp);
                    postError(callback, "no emoji found");
                    return;
                }
                File dir = packDir();
                deleteRecursive(dir);
                if (!tmp.renameTo(dir)) {
                    deleteRecursive(tmp);
                    cachedCount = 0;
                    postError(callback, "can't install pack");
                    return;
                }
                cachedCount = count;
                final int total = count;
                AndroidUtilities.runOnUIThread(() -> callback.onComplete(total));
            } catch (Throwable e) {
                FileLog.e(e);
                deleteRecursive(tmp);
                cachedCount = -1; // packDir state uncertain after a mid-extract throw — recompute lazily
                postError(callback, e.getMessage());
            }
        });
    }

    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private static void postProgress(ProgressCallback cb, int done) {
        AndroidUtilities.runOnUIThread(() -> cb.onProgress(done));
    }

    private static void postError(ProgressCallback cb, String message) {
        AndroidUtilities.runOnUIThread(() -> cb.onError(message));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }
}
