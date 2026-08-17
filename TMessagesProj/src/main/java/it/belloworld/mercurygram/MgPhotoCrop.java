package it.belloworld.mercurygram;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Pair;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.ui.PhotoViewer;

import java.io.File;

/**
 * Renders a crop straight from the source file instead of from an
 * already-downscaled bitmap.
 *
 * Upstream decodes the whole photo scaled down to the send size and only then
 * cuts the selected region out of it, so a crop covering a quarter of the frame
 * comes out at a quarter of the target resolution. Here the source is decoded
 * with the largest sample size that still leaves the *cropped region* at or
 * above the target, so the crop keeps the resolution the send size promises.
 */
public class MgPhotoCrop {

    private MgPhotoCrop() {
    }

    /**
     * @return the cropped bitmap, or null when the caller must fall back to the
     *         upstream path (no crop, painted overlay, missing source, source
     *         already small enough, decode failure).
     */
    public static Bitmap renderHighQualityCrop(MediaController.MediaEditState entry, int targetSize) {
        final MediaController.CropState cropState = entry.cropState;
        // A live photo is not isVideo, but the caller still adopts the returned bitmap as
        // the one it keeps on screen, where a crop of up to twice the send size per axis
        // would stay resident instead of the small thumbnail upstream produces.
        if (cropState == null || entry.isVideo || entry.isLivePhoto() || entry.paintPath != null || targetSize <= 0) {
            return null;
        }
        final String path = entry.filterPath != null && !entry.filterPath.isEmpty() ? entry.filterPath : entry.getPath();
        if (path == null || path.isEmpty() || !new File(path).exists()) {
            return null;
        }

        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        final int width = bounds.outWidth;
        final int height = bounds.outHeight;
        if (width <= 0 || height <= 0 || Math.max(width, height) <= targetSize) {
            return null;
        }

        final Pair<Integer, Integer> exif = AndroidUtilities.getImageOrientation(path);
        final int sample = computeSampleSize(width, height, cropState, exif.first, targetSize, memoryBudget());
        if (sample <= 0) {
            return null;
        }

        final BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError e) {
            bitmap = null;
        }
        if (bitmap == null) {
            return null;
        }
        try {
            return PhotoViewer.createCroppedBitmap(bitmap, cropState, new int[]{exif.first, exif.second}, true);
        } finally {
            bitmap.recycle();
        }
    }

    static long memoryBudget() {
        // The whole bake runs on the main thread, so the budget doubles as a stall bound.
        // Dropping it costs at most one sample step, and only for a small crop of a very
        // large source, where the result still comes out well above the upstream path.
        return Math.min(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4);
    }

    /**
     * Largest power-of-two sample size that keeps the cropped output at or above
     * {@code targetSize} on its long side, raised further if the decode would not
     * fit in {@code maxBytes}. Returns 0 when the crop is degenerate.
     */
    static int computeSampleSize(int width, int height, MediaController.CropState cropState, int exifRotation, int targetSize, long maxBytes) {
        final int rotation = (cropState.transformRotation + exifRotation) % 360;
        final boolean swapped = rotation == 90 || rotation == 270;
        final int rotatedWidth = swapped ? height : width;
        final int rotatedHeight = swapped ? width : height;
        final float cropWidth = rotatedWidth * cropState.cropPw;
        final float cropHeight = rotatedHeight * cropState.cropPh;
        final float cropLongSide = Math.max(cropWidth, cropHeight);
        if (cropLongSide <= 0f) {
            return 0;
        }
        int sample = 1;
        while (cropLongSide / (sample * 2) >= targetSize) {
            sample *= 2;
        }
        // The source and the cropped copy are alive at the same time, and an
        // OutOfMemoryError is process-wide: budgeting only the source lets the peak
        // reach roughly twice the budget and take down an unrelated decode instead.
        while (decodedBytes(width, height, cropWidth, cropHeight, sample) > maxBytes) {
            sample *= 2;
        }
        return sample;
    }

    static long decodedBytes(int width, int height, float cropWidth, float cropHeight, int sample) {
        final long source = (long) (width / sample) * (height / sample);
        final long crop = (long) (cropWidth / sample) * (long) (cropHeight / sample);
        return (source + crop) * 4L;
    }
}
