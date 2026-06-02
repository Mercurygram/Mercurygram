package it.belloworld.mercurygram.emoji;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.SparseIntArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * matches glyph entries on the <b>basename</b> only, so a plain .zip of loose
 * PNGs, a re-zipped {@code emoji/} folder, AND a raw Telegram {@code .apk}
 * (emoji live at {@code assets/emoji/...} inside it) all work through the same
 * path.
 *
 * Telegram's own glyph PNGs are <b>opaque</b> (indexed-color, no alpha); the
 * alpha channel lives separately in {@code emoji/masks/<maskId>.png} and the
 * glyph→mask map in {@code emoji/metadata.bin}. The importer extracts those too
 * (when present), and {@link #loadEmojiBitmap} composites glyph-RGB with
 * mask-alpha — without it an APK-sourced pack renders every glyph on a black
 * background. A loose .zip of already-ARGB PNGs ships no {@code metadata.bin},
 * so the mask step auto-skips and the glyphs are used verbatim.
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
    // Telegram stores glyph color (opaque palette PNG) and its alpha channel
    // separately: emoji/masks/<maskId>.png (grayscale) + emoji/metadata.bin
    // (glyphIndex→maskId map). We extract both so loadEmojiBitmap can composite
    // them — otherwise an APK-sourced pack renders every glyph on a black
    // background. Matched on the full entry path (the directory segment is what
    // distinguishes a mask 5.png from a glyph 5_0.png); output paths are rebuilt
    // from the captured number / fixed name, so this stays zip-slip safe.
    private static final Pattern MASK_ENTRY = Pattern.compile("(^|/)masks/(\\d+)\\.png$");
    private static final Pattern METADATA_ENTRY = Pattern.compile("(^|/)metadata\\.bin$");
    private static final String MASKS_SUBDIR = "masks";
    private static final String METADATA_FILE = "metadata.bin";
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

    // Glyph(page*4096+page2) → maskId map parsed lazily from packDir/metadata.bin.
    // null = not yet read; non-null = read (an empty map means "no metadata.bin"
    // — i.e. a loose ARGB pack, so every lookup misses and masking is skipped).
    // Reset to null alongside cachedCount on remove / import-complete / failed import.
    private static volatile SparseIntArray maskMap;

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
                    int sample = AndroidUtilities.density <= 1.0f ? 2 : 1;
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = sample;
                    Bitmap bitmap = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                    if (bitmap != null) {
                        return applyMask(bitmap, page, page2, sample);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return Emoji.loadBitmap("emoji/" + String.format(Locale.US, "%d_%d.png", page, page2));
    }

    /**
     * Telegram glyph PNGs are opaque; their alpha lives in a separate grayscale
     * mask. If the imported pack carries a metadata.bin map and the mask file
     * for this glyph exists, composite glyph-RGB + mask-alpha into a fresh
     * ARGB_8888 bitmap (faithful port of {@code Emoji.loadEmoji}). When no
     * metadata/mask is present (e.g. a loose ARGB .zip), the glyph is returned
     * unchanged. The mask is decoded at the same {@code inSampleSize} as the
     * glyph so the two pixel grids line up.
     */
    private static Bitmap applyMask(Bitmap bitmap, int page, int page2, int sample) {
        int maskId = maskMap().get(page * 4096 + page2, -1);
        if (maskId == -1) {
            return bitmap;
        }
        File maskFile = new File(new File(packDir(), MASKS_SUBDIR), maskId + ".png");
        Bitmap alphaBitmap = null;
        try {
            // decodeFile returns null for a missing/corrupt mask file, which the
            // guard below already handles — no separate exists() stat needed.
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            alphaBitmap = BitmapFactory.decodeFile(maskFile.getAbsolutePath(), opts);
            if (alphaBitmap == null
                    || alphaBitmap.getWidth() != bitmap.getWidth()
                    || alphaBitmap.getHeight() != bitmap.getHeight()) {
                return bitmap;
            }
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] rgbPixels = new int[w * h];
            int[] alphaPixels = new int[w * h];
            bitmap.getPixels(rgbPixels, 0, w, 0, 0, w, h);
            alphaBitmap.getPixels(alphaPixels, 0, w, 0, 0, w, h);
            for (int i = 0; i < rgbPixels.length; i++) {
                rgbPixels[i] = (rgbPixels[i] & 0x00FFFFFF) | ((alphaPixels[i] & 0xFF) << 24);
            }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(rgbPixels, 0, w, 0, 0, w, h);
            bitmap.recycle();
            return out;
        } catch (Throwable e) {
            FileLog.e(e);
            return bitmap;
        } finally {
            if (alphaBitmap != null) {
                alphaBitmap.recycle();
            }
        }
    }

    /**
     * Lazily parse packDir/metadata.bin into a glyph→maskId map (little-endian
     * pairs of unsigned shorts, key {@code page*4096+page2}), mirroring
     * {@code Emoji.loadEmojiAlphaMasks}. Always returns non-null and is cached;
     * an empty map means "no metadata.bin" (loose ARGB pack) so every lookup
     * misses and masking is skipped — and the file isn't re-stat'd per glyph.
     * Invalidated by {@link #invalidateMaskMap} on import / remove.
     */
    private static SparseIntArray maskMap() {
        SparseIntArray cached = maskMap;
        if (cached != null) {
            return cached;
        }
        synchronized (MgEmojiPack.class) {
            if (maskMap != null) {
                return maskMap;
            }
            SparseIntArray map = new SparseIntArray();
            try {
                File meta = new File(packDir(), METADATA_FILE);
                if (meta.isFile()) {
                    // Plain stream read — java.nio.file.Files is API 26+ and not
                    // desugared in this build (minSdk 24); mirrors the manual
                    // read loop in Emoji.loadEmojiAlphaMasks. Size is bounded by
                    // MAX_ENTRY_BYTES at import, so the int cast / array is safe.
                    byte[] all = readAllBytes(meta);
                    ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
                    int pairs = all.length / 4;
                    for (int i = 0; i < pairs; i++) {
                        int glyphIndex = bb.getShort() & 0xFFFF;
                        int maskId = bb.getShort() & 0xFFFF;
                        map.put(glyphIndex, maskId);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
                map.clear(); // partial parse → drop it; treat as no masks
            }
            maskMap = map;
            return map;
        }
    }

    private static void invalidateMaskMap() {
        maskMap = null;
    }

    public static void remove() {
        deleteRecursive(packDir());
        cachedCount = 0;
        invalidateMaskMap();
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
                int auxCount = 0;
                File masksDir = new File(tmp, MASKS_SUBDIR);
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
                        // Resolve the output file. Glyphs match on the basename
                        // (lenient — directory prefix ignored); masks + metadata
                        // match on the full entry path (the `masks/` segment is
                        // what tells a mask 5.png apart from a glyph 5_0.png).
                        // Output paths are rebuilt from the captured number /
                        // fixed name, never the raw entry path → zip-slip safe.
                        String name = entry.getName();
                        String base = baseName(name);
                        File out;
                        boolean isGlyph;
                        Matcher mm;
                        if (EMOJI_ENTRY.matcher(base).matches()) {
                            out = new File(tmp, base);
                            isGlyph = true;
                        } else if ((mm = MASK_ENTRY.matcher(name)).find()) {
                            if (auxCount >= MAX_ENTRIES) {
                                continue;
                            }
                            if (!masksDir.isDirectory() && !masksDir.mkdirs()) {
                                continue;
                            }
                            out = new File(masksDir, mm.group(2) + ".png");
                            isGlyph = false;
                        } else if (METADATA_ENTRY.matcher(name).find()) {
                            out = new File(tmp, METADATA_FILE);
                            isGlyph = false;
                        } else {
                            continue;
                        }
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
                        if (!isGlyph) {
                            auxCount++;
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
                    invalidateMaskMap();
                    postError(callback, "can't install pack");
                    return;
                }
                cachedCount = count;
                invalidateMaskMap(); // new pack content → reload metadata.bin lazily
                final int total = count;
                AndroidUtilities.runOnUIThread(() -> callback.onComplete(total));
            } catch (Throwable e) {
                FileLog.e(e);
                deleteRecursive(tmp);
                cachedCount = -1; // packDir state uncertain after a mid-extract throw — recompute lazily
                invalidateMaskMap();
                postError(callback, e.getMessage());
            }
        });
    }

    // metadata.bin is capped at MAX_ENTRY_BYTES at import time, so its on-disk
    // length is known and bounded — read it into one right-sized buffer rather
    // than growing dynamically. Returns exactly the bytes read.
    private static byte[] readAllBytes(File f) throws Exception {
        int size = (int) Math.min(f.length(), MAX_ENTRY_BYTES);
        byte[] out = new byte[size];
        try (InputStream is = new FileInputStream(f)) {
            int total = 0;
            int read;
            while (total < size && (read = is.read(out, total, size - total)) != -1) {
                total += read;
            }
            return total == size ? out : java.util.Arrays.copyOf(out, total);
        }
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
