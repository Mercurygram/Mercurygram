package it.belloworld.mercurygram;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Pair;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.Stories.recorder.StoryEntry;

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
     *         already small enough, not enough memory, decode failure).
     */
    public static Bitmap renderHighQualityCrop(MediaController.MediaEditState entry, int targetSize) {
        final MediaController.CropState cropState = entry.cropState;
        // A live photo is not isVideo, but the caller still adopts the returned bitmap as
        // the one it keeps on screen, where a crop of up to twice the send size per axis
        // would stay resident instead of the small thumbnail upstream produces.
        if (cropState == null || entry.isVideo || entry.isLivePhoto() || entry.paintPath != null || targetSize <= 0) {
            return null;
        }
        final String path = sourcePath(entry);
        if (path == null) {
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

        final int[] orientation = sourceOrientation(entry);
        final int sample = computeSampleSize(width, height, cropState, orientation[0], targetSize, memoryBudget());
        if (sample <= 0) {
            return null;
        }

        final BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError e) {
            bitmap = null;
        }
        return cropAndRecycle(bitmap, cropState, orientation);
    }

    /**
     * Bakes the crop for the viewer, cheapest source first: the source on disk at full
     * resolution, then the viewer's own bitmap, then the source on disk at the send
     * size. {@code viewerBitmap} may be null - it is trimmed under memory pressure.
     * @return null when nothing readable is left to cut the crop out of.
     */
    public static Bitmap bakeCropForViewer(MediaController.MediaEditState entry, Bitmap viewerBitmap, int[] viewerOrientation, int targetSize) {
        Bitmap cropped = renderHighQualityCrop(entry, targetSize);
        if (cropped == null && viewerBitmap != null) {
            cropped = PhotoViewer.createCroppedBitmap(viewerBitmap, entry.cropState, viewerOrientation, true);
        }
        return cropped != null ? cropped : rebakeCroppedBitmap(entry);
    }

    /** The edit source: the filtered image when there is one, else the original. */
    private static String sourcePath(MediaController.MediaEditState entry) {
        final String path = entry.filterPath != null && !entry.filterPath.isEmpty() ? entry.filterPath : entry.getPath();
        return path == null || path.isEmpty() || !new File(path).exists() ? null : path;
    }

    /**
     * The orientation the editor showed the source with, which is the frame the crop
     * rectangle was drawn in. Reading the file's EXIF instead is a second source of
     * truth: the viewer applies MediaController.PhotoEntry's MediaStore orientation,
     * which can be stale and never carries the EXIF mirror flags, so the two disagree
     * on exactly the photos where the difference is a visibly different region.
     */
    public static int[] sourceOrientation(MediaController.MediaEditState entry) {
        if (entry.filterPath != null && !entry.filterPath.isEmpty()) {
            // the filtered copy is written upright and carries no EXIF of its own
            return new int[]{0, 0};
        }
        if (entry instanceof MediaController.PhotoEntry) {
            final MediaController.PhotoEntry photo = (MediaController.PhotoEntry) entry;
            return new int[]{photo.orientation, photo.invert};
        }
        final Pair<Integer, Integer> exif = AndroidUtilities.getImageOrientation(entry.getPath());
        return new int[]{exif.first, exif.second};
    }

    /**
     * Crop bake for when the viewer has no in-memory bitmap left, or cropping it
     * failed: re-decodes the source from disk at the send size and cuts the crop
     * out of it. @return null when the source is unreadable.
     */
    public static Bitmap rebakeCroppedBitmap(MediaController.MediaEditState entry) {
        final String path = sourcePath(entry);
        if (entry.cropState == null || path == null) {
            return null;
        }
        final int size = AndroidUtilities.getPhotoSize(true);
        return cropAndRecycle(StoryEntry.getScaledBitmap(opts -> BitmapFactory.decodeFile(path, opts), size, size, false, true), entry.cropState, sourceOrientation(entry));
    }

    /** Cuts {@code cropState} out of a decoded source and releases the source. */
    private static Bitmap cropAndRecycle(Bitmap source, MediaController.CropState cropState, int[] orientation) {
        if (source == null) {
            return null;
        }
        try {
            return PhotoViewer.createCroppedBitmap(source, cropState, orientation, true);
        } finally {
            source.recycle();
        }
    }

    /** What is actually free right now, with the same headroom StoryEntry.getScaledBitmap keeps. */
    static long memoryBudget() {
        final Runtime runtime = Runtime.getRuntime();
        final long available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        return (long) (available / 1.1f);
    }

    /**
     * Largest power-of-two sample size that keeps the cropped output at or above
     * {@code targetSize} on its long side, never coarser than the upstream path
     * would have decoded, raised within that bound if the decode would not fit in
     * {@code maxBytes}. Returns 0 when the crop is degenerate or when even the
     * upstream-equivalent decode does not fit, so the caller falls back.
     */
    static int computeSampleSize(int width, int height, MediaController.CropState cropState, int sourceRotation, int targetSize, long maxBytes) {
        final int rotation = (cropState.transformRotation + sourceRotation) % 360;
        final boolean swapped = rotation == 90 || rotation == 270;
        final int rotatedWidth = swapped ? height : width;
        final int rotatedHeight = swapped ? width : height;
        final float cropWidth = rotatedWidth * cropState.cropPw;
        final float cropHeight = rotatedHeight * cropState.cropPh;
        // a zoomed-in crop magnifies its region, so fewer source pixels feed the output
        // than the crop percentages alone suggest
        final float cropLongSide = Math.max(cropWidth, cropHeight) / Math.max(1f, cropState.cropScale);
        if (cropLongSide <= 0f) {
            return 0;
        }
        int sample = 1;
        while (cropLongSide / (sample * 2) >= targetSize) {
            sample *= 2;
        }
        // Never decode coarser than the upstream path: StoryEntry.getScaledBitmap covers
        // the target box rather than fitting into it, so it keeps the SHORT side at the
        // target. Matching that on both axes is what makes this a strict improvement
        // instead of a trade, whatever the crop and the memory pressure turn out to be.
        int coarsest = 1;
        while (Math.min(width, height) / (coarsest * 2) >= targetSize) {
            coarsest *= 2;
        }
        if (sample > coarsest) {
            sample = coarsest;
        }
        // The source and the cropped copy are alive at the same time, and an
        // OutOfMemoryError is process-wide: budgeting only the source lets the peak
        // reach roughly twice the budget and take down an unrelated decode instead.
        while (sample < coarsest && decodedBytes(width, height, cropWidth, cropHeight, sample) > maxBytes) {
            sample *= 2;
        }
        return decodedBytes(width, height, cropWidth, cropHeight, sample) > maxBytes ? 0 : sample;
    }

    static long decodedBytes(int width, int height, float cropWidth, float cropHeight, int sample) {
        final long source = (long) (width / sample) * (height / sample);
        final long crop = (long) (cropWidth / sample) * (long) (cropHeight / sample);
        return (source + crop) * 4L;
    }
}
