package it.belloworld.mercurygram.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;

import androidx.core.util.Consumer;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.snapshotter.MapSnapshotter;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Renders the static location preview shown in chat bubbles on-device with
 * MapLibre's {@link MapSnapshotter}, using the same OpenFreeMap vector style as
 * the interactive map ({@code MapLibreMapsProvider}). No third-party static-map
 * service is contacted, only {@code tiles.openfreemap.org}. The result is cached
 * on disk under Telegram's managed media-cache dir (so it is counted and freed by
 * Settings &gt; Data and Storage and "Clear cache") and in memory so scrolling does
 * not re-render or re-fetch.
 *
 * <p>The bookkeeping maps below are touched only on the UI thread; the only work
 * pushed to {@link Utilities#globalQueue} is disk I/O, which hops back via
 * {@link AndroidUtilities#runOnUIThread}. The cross-thread {@link #ready} cache is
 * a {@link java.util.concurrent.ConcurrentHashMap}.
 */
public final class MgMapSnapshot {

    // Same OpenFreeMap style used by MapLibreMapsProvider for the interactive map.
    private static final String STYLE_POSITRON = "https://tiles.openfreemap.org/styles/positron";
    private static final int ZOOM = 15;
    // Cap concurrent native snapshotters so heavy scrolling can't exhaust GL/native memory.
    private static final int MAX_CONCURRENT = 2;

    /** One in-flight render: the callers waiting on it plus its (nullable) snapshotter. */
    private static final class Render {
        final ArrayList<Consumer<String>> waiters = new ArrayList<>();
        MapSnapshotter snapshotter;
    }

    // UI-thread only.
    private static final HashMap<String, Render> renders = new HashMap<>();
    private static final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private static int activeCount = 0;

    // Cross-thread memory cache: key -> ready PNG path. Lets repeated binds skip disk I/O entirely.
    private static final java.util.concurrent.ConcurrentHashMap<String, String> ready = new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile Bitmap pinCache;

    private MgMapSnapshot() {
    }

    /**
     * Returns the cached PNG path for the given location, rendering it if needed.
     * {@code onReady} is invoked on the UI thread with the file path on success;
     * it is not invoked on failure (the caller keeps showing its placeholder).
     * Must be called on the UI thread.
     */
    public static void get(Context context, double lat, double lon, int wPx, int hPx, Consumer<String> onReady) {
        if (context == null || onReady == null) {
            return;
        }
        final String key = cacheKey(lat, lon, wPx, hPx);
        final String mem = ready.get(key);
        if (mem != null) {
            onReady.accept(mem);
            return;
        }
        // Join an already in-flight render directly (renders is UI-thread only) to skip a disk hop.
        Render r = renders.get(key);
        if (r != null) {
            r.waiters.add(onReady);
            return;
        }
        final Context app = context.getApplicationContext();
        // Move the existence check off the UI thread to avoid scroll jank.
        Utilities.globalQueue.postRunnable(() -> {
            final File file = cacheFile(key);
            if (file.exists() && file.length() > 0) {
                final String path = file.getAbsolutePath();
                ready.put(key, path);
                AndroidUtilities.runOnUIThread(() -> onReady.accept(path));
                return;
            }
            AndroidUtilities.runOnUIThread(() -> enqueueRender(app, lat, lon, wPx, hPx, key, file, onReady));
        });
    }

    private static void enqueueRender(Context app, double lat, double lon, int wPx, int hPx, String key, File file, Consumer<String> onReady) {
        Render r = renders.get(key);
        if (r != null) {
            r.waiters.add(onReady);
            return;
        }
        r = new Render();
        r.waiters.add(onReady);
        renders.put(key, r);

        Runnable starter = () -> doStart(app, lat, lon, wPx, hPx, key, file);
        if (activeCount >= MAX_CONCURRENT) {
            queue.add(starter);
        } else {
            activeCount++;
            starter.run();
        }
    }

    private static void doStart(Context app, double lat, double lon, int wPx, int hPx, String key, File file) {
        try {
            MapLibre.getInstance(app);

            float ratio = Math.max(1f, AndroidUtilities.density);
            int wDp = Math.max(1, Math.round(wPx / ratio));
            int hDp = Math.max(1, Math.round(hPx / ratio));

            CameraPosition camera = new CameraPosition.Builder()
                    .target(new LatLng(lat, lon))
                    .zoom(ZOOM)
                    .build();
            MapSnapshotter.Options options = new MapSnapshotter.Options(wDp, hDp)
                    .withStyle(STYLE_POSITRON)
                    .withCameraPosition(camera)
                    .withPixelRatio(ratio)
                    .withLogo(false)
                    .withAttribution(false);

            MapSnapshotter snapshotter = new MapSnapshotter(app, options);
            Render r = renders.get(key);
            if (r != null) {
                r.snapshotter = snapshotter;
            }
            snapshotter.start(
                    snapshot -> onSnapshotReady(app, key, file, snapshot.getBitmap()),
                    error -> {
                        FileLog.e("MgMapSnapshot render failed: " + error);
                        finishFailure(key);
                    });
        } catch (Throwable e) {
            FileLog.e(e);
            finishFailure(key);
        }
    }

    private static void onSnapshotReady(Context app, String key, File file, Bitmap bitmap) {
        releaseSnapshotter(key);
        if (bitmap == null) {
            finishFailure(key);
            return;
        }
        drawMarker(app, bitmap);
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = writePngAtomic(bitmap, file);
            try {
                bitmap.recycle();
            } catch (Throwable ignore) {
            }
            AndroidUtilities.runOnUIThread(() -> finishSuccess(key, ok ? file.getAbsolutePath() : null));
        });
    }

    private static void finishSuccess(String key, String path) {
        Render r = renders.remove(key);
        releaseSlot();
        if (path == null) {
            return;
        }
        ready.put(key, path);
        if (r != null) {
            for (int i = 0; i < r.waiters.size(); i++) {
                r.waiters.get(i).accept(path);
            }
        }
    }

    private static void finishFailure(String key) {
        releaseSnapshotter(key);
        // Drop the render so a later re-bind can retry (e.g. after network recovers).
        renders.remove(key);
        releaseSlot();
    }

    private static void releaseSnapshotter(String key) {
        Render r = renders.get(key);
        if (r != null && r.snapshotter != null) {
            try {
                r.snapshotter.cancel();
            } catch (Throwable ignore) {
            }
            r.snapshotter = null;
        }
    }

    private static void releaseSlot() {
        if (activeCount > 0) {
            activeCount--;
        }
        if (!queue.isEmpty() && activeCount < MAX_CONCURRENT) {
            activeCount++;
            queue.poll().run();
        }
    }

    private static void drawMarker(Context context, Bitmap target) {
        try {
            Bitmap pin = pinCache;
            if (pin == null || pin.isRecycled()) {
                pin = BitmapFactory.decodeResource(context.getResources(), R.drawable.map_pin);
                pinCache = pin;
            }
            if (pin == null) {
                return;
            }
            Canvas canvas = new Canvas(target);
            // Pin tip points down; anchor its bottom-center at the bitmap centre (the camera target).
            float x = (target.getWidth() - pin.getWidth()) / 2f;
            float y = target.getHeight() / 2f - pin.getHeight();
            canvas.drawBitmap(pin, x, y, null);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static boolean writePngAtomic(Bitmap bitmap, File file) {
        File tmp = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            if (tmp.renameTo(file)) {
                return true;
            }
            tmp.delete();
            return false;
        } catch (Throwable e) {
            FileLog.e(e);
            if (tmp != null) {
                tmp.delete();
            }
            return false;
        }
    }

    private static File cacheFile(String key) {
        // Flat file in Telegram's managed media-cache dir so it's counted and freed by the
        // app's own cache management (not a separate, invisible cache).
        return new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "mgmap_" + key + ".png");
    }

    private static String cacheKey(double lat, double lon, int wPx, int hPx) {
        // Integer microdegrees keep the key stable without per-bind String.format/boxing.
        return new StringBuilder(40)
                .append(Math.round(lat * 1e6)).append('_')
                .append(Math.round(lon * 1e6)).append('_')
                .append(ZOOM).append('_')
                .append(wPx).append('_')
                .append(hPx)
                .toString();
    }
}
