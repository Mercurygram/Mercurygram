package org.telegram.messenger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.util.Consumer;

import org.maplibre.android.MapLibre;
import org.maplibre.android.annotations.Icon;
import org.maplibre.android.annotations.IconFactory;
import org.maplibre.android.annotations.Marker;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.annotations.Polygon;
import org.maplibre.android.annotations.PolygonOptions;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

import java.util.ArrayList;
import java.util.List;

public class MapLibreMapsProvider implements IMapsProvider {

    private static final String STYLE_LIBERTY  = "https://tiles.openfreemap.org/styles/liberty";
    private static final String STYLE_POSITRON = "https://tiles.openfreemap.org/styles/positron";

    @Override
    public void initializeMaps(Context context) {
        MapLibre.getInstance(context);
    }

    @Override
    public IMapView onCreateMapView(Context context) {
        return new MapLibreMapView(context);
    }

    @Override
    public IMarkerOptions onCreateMarkerOptions() {
        return new MapLibreMarkerOptions();
    }

    @Override
    public ICircleOptions onCreateCircleOptions() {
        return new MapLibreCircleOptions();
    }

    @Override
    public ILatLngBoundsBuilder onCreateLatLngBoundsBuilder() {
        return new MapLibreLatLngBoundsBuilder();
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLng(IMapsProvider.LatLng latLng) {
        return new MapLibreCameraUpdate(latLng, -1);
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngZoom(IMapsProvider.LatLng latLng, float zoom) {
        return new MapLibreCameraUpdate(latLng, zoom);
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngBounds(ILatLngBounds bounds, int padding) {
        return new MapLibreCameraUpdateBounds((MapLibreLatLngBounds) bounds, padding);
    }

    @Override
    public IMapStyleOptions loadRawResourceStyle(Context context, int resId) {
        return new MapLibreStyleOptions(STYLE_POSITRON);
    }

    @Override
    public String getMapsAppPackageName() {
        return "net.osmand";
    }

    @Override
    public int getInstallMapsString() {
        return 0;
    }

    @Override
    public boolean supportsMapTypes() {
        return false;
    }

    // --- Camera update wrappers ---

    static class MapLibreCameraUpdate implements ICameraUpdate {
        final IMapsProvider.LatLng latLng;
        final float zoom;

        MapLibreCameraUpdate(IMapsProvider.LatLng latLng, float zoom) {
            this.latLng = latLng;
            this.zoom = zoom;
        }
    }

    static class MapLibreCameraUpdateBounds implements ICameraUpdate {
        final MapLibreLatLngBounds bounds;
        final int padding;

        MapLibreCameraUpdateBounds(MapLibreLatLngBounds bounds, int padding) {
            this.bounds = bounds;
            this.padding = padding;
        }
    }

    static class MapLibreStyleOptions implements IMapStyleOptions {
        final String styleUrl;

        MapLibreStyleOptions(String styleUrl) {
            this.styleUrl = styleUrl;
        }
    }

    // --- IMap implementation ---

    static class MapLibreMapImpl implements IMap {
        private final MapView mapView;
        private final MapLibreMap mapLibreMap;
        private boolean nightMode = false;
        private boolean myLocationEnabled = false;
        private LocationManager locationManager;
        private LocationListener locationSelfListener;
        private Marker myLocationNativeMarker;
        private MarkerOptions myLocationNativeOptions;
        private Consumer<Location> myLocationChangeListener;
        // Guard: don't forward onCameraIdle until the camera has been intentionally moved.
        // MapLibre fires onCameraIdle immediately on style load (camera at 0,0), which would
        // trigger geocoding of "Atlantic Ocean" before the real position is applied.
        private boolean hasCameraMoved = false;
        private Runnable onCameraIdleListener;
        private Runnable onCameraMoveListener;
        private OnCameraMoveStartedListener onCameraMoveStartedListener;

        // Annotation tracking for re-add after style change.
        // Markers are represented as parallel lists: each index corresponds to one marker.
        private final List<MarkerOptions> markerOptionsList = new ArrayList<>();
        private final List<Marker[]>      markerHolders     = new ArrayList<>(); // mutable holder
        private final List<IMarker>       iMarkerList       = new ArrayList<>();

        MapLibreMapImpl(MapView mapView, MapLibreMap mapLibreMap) {
            this.mapView = mapView;
            this.mapLibreMap = mapLibreMap;
            mapLibreMap.addOnCameraIdleListener(() -> {
                if (hasCameraMoved && onCameraIdleListener != null) onCameraIdleListener.run();
            });
            mapLibreMap.addOnCameraMoveStartedListener(reason -> {
                hasCameraMoved = true;
                if (onCameraMoveStartedListener != null) onCameraMoveStartedListener.onCameraMoveStarted(reason);
            });
            mapLibreMap.addOnCameraMoveListener(() -> {
                if (onCameraMoveListener != null) onCameraMoveListener.run();
            });
        }

        private String getStyleUrl() {
            return nightMode ? STYLE_POSITRON : STYLE_LIBERTY;
        }

        /**
         * Re-add all tracked markers after a style change.
         * MapLibre clears deprecated annotations on every setStyle call; we rebuild them
         * using the stored MarkerOptions and update the mutable Marker[] holders so that
         * existing IMarker references held by callers continue to work.
         */
        private void reAddMarkersAfterStyleChange() {
            for (int i = 0; i < markerHolders.size(); i++) {
                Marker newMarker = mapLibreMap.addMarker(markerOptionsList.get(i));
                markerHolders.get(i)[0] = newMarker;
            }
            if (pendingMarkerClickListener != null) {
                setOnMarkerClickListener(pendingMarkerClickListener);
            }
            if (myLocationEnabled && myLocationNativeOptions != null) {
                myLocationNativeMarker = mapLibreMap.addMarker(myLocationNativeOptions);
            }
        }

        private void startLocationUpdates() {
            Context ctx = mapView.getContext().getApplicationContext();
            locationManager = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            locationSelfListener = location -> {
                updateMyLocationDot(location);
                if (myLocationChangeListener != null) myLocationChangeListener.accept(location);
            };
            try {
                boolean hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                boolean hasNet = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                if (hasGps) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 1, locationSelfListener, Looper.getMainLooper());
                }
                if (hasNet) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 1, locationSelfListener, Looper.getMainLooper());
                }
                Location last = null;
                if (hasGps) last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last == null && hasNet) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (last != null) {
                    updateMyLocationDot(last);
                    if (myLocationChangeListener != null) myLocationChangeListener.accept(last);
                }
            } catch (SecurityException e) {
                FileLog.e(e);
            }
        }

        private void stopLocationUpdates() {
            if (locationManager != null && locationSelfListener != null) {
                try { locationManager.removeUpdates(locationSelfListener); } catch (Exception ignored) {}
                locationSelfListener = null;
            }
            if (myLocationNativeMarker != null) {
                try { mapLibreMap.removeAnnotation(myLocationNativeMarker); } catch (Exception ignored) {}
                myLocationNativeMarker = null;
                myLocationNativeOptions = null;
            }
        }

        private void updateMyLocationDot(Location location) {
            org.maplibre.android.geometry.LatLng ll =
                    new org.maplibre.android.geometry.LatLng(location.getLatitude(), location.getLongitude());
            if (myLocationNativeMarker == null) {
                myLocationNativeOptions = new MarkerOptions();
                myLocationNativeOptions.position(ll);
                myLocationNativeOptions.icon(IconFactory.getInstance(mapView.getContext()).fromBitmap(createBlueDotBitmap()));
                myLocationNativeMarker = mapLibreMap.addMarker(myLocationNativeOptions);
            } else {
                myLocationNativeMarker.setPosition(ll);
                myLocationNativeOptions.position(ll);
            }
        }

        private static Bitmap createBlueDotBitmap() {
            int size = 48;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
            paint.setColor(0xFF4285F4);
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 5, paint);
            return bmp;
        }

        private OnMarkerClickListener pendingMarkerClickListener;

        @Override
        public void setMapType(int mapType) {
            // Map types not supported (supportsMapTypes() returns false); this is never called.
        }

        @Override
        public void animateCamera(ICameraUpdate update) {
            applyCameraAnimated(update, 300, null);
        }

        @Override
        public void animateCamera(ICameraUpdate update, ICancelableCallback callback) {
            applyCameraAnimated(update, 300, callback);
        }

        @Override
        public void animateCamera(ICameraUpdate update, int duration, ICancelableCallback callback) {
            applyCameraAnimated(update, duration, callback);
        }

        @Override
        public void moveCamera(ICameraUpdate update) {
            hasCameraMoved = true;
            if (update instanceof MapLibreCameraUpdate) {
                MapLibreCameraUpdate u = (MapLibreCameraUpdate) update;
                org.maplibre.android.geometry.LatLng ll = new org.maplibre.android.geometry.LatLng(u.latLng.latitude, u.latLng.longitude);
                if (u.zoom > 0) {
                    mapLibreMap.moveCamera(CameraUpdateFactory.newLatLngZoom(ll, u.zoom));
                } else {
                    mapLibreMap.moveCamera(CameraUpdateFactory.newLatLng(ll));
                }
            } else if (update instanceof MapLibreCameraUpdateBounds) {
                MapLibreCameraUpdateBounds u = (MapLibreCameraUpdateBounds) update;
                if (u.bounds != null && u.bounds.latLngBounds != null) {
                    mapLibreMap.moveCamera(CameraUpdateFactory.newLatLngBounds(u.bounds.latLngBounds, u.padding));
                }
            }
        }

        private void applyCameraAnimated(ICameraUpdate update, int duration, ICancelableCallback callback) {
            hasCameraMoved = true;
            MapLibreMap.CancelableCallback cb = callback == null ? null : new MapLibreMap.CancelableCallback() {
                @Override public void onFinish() { callback.onFinish(); }
                @Override public void onCancel() { callback.onCancel(); }
            };
            if (update instanceof MapLibreCameraUpdate) {
                MapLibreCameraUpdate u = (MapLibreCameraUpdate) update;
                org.maplibre.android.geometry.LatLng ll = new org.maplibre.android.geometry.LatLng(u.latLng.latitude, u.latLng.longitude);
                org.maplibre.android.camera.CameraUpdate cu = u.zoom > 0
                        ? CameraUpdateFactory.newLatLngZoom(ll, u.zoom)
                        : CameraUpdateFactory.newLatLng(ll);
                mapLibreMap.animateCamera(cu, duration, cb);
            } else if (update instanceof MapLibreCameraUpdateBounds) {
                MapLibreCameraUpdateBounds u = (MapLibreCameraUpdateBounds) update;
                if (u.bounds != null && u.bounds.latLngBounds != null) {
                    mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngBounds(u.bounds.latLngBounds, u.padding), duration, cb);
                } else if (cb != null) {
                    cb.onFinish();
                }
            } else if (cb != null) {
                cb.onFinish();
            }
        }

        @Override
        public float getMaxZoomLevel() {
            return (float) mapLibreMap.getMaxZoomLevel();
        }

        @Override
        public float getMinZoomLevel() {
            return (float) mapLibreMap.getMinZoomLevel();
        }

        @Override
        public void setMyLocationEnabled(boolean enabled) {
            myLocationEnabled = enabled;
            if (enabled) {
                startLocationUpdates();
            } else {
                stopLocationUpdates();
            }
        }

        @Override
        public IUISettings getUiSettings() {
            org.maplibre.android.maps.UiSettings ui = mapLibreMap.getUiSettings();
            return new IUISettings() {
                @Override public void setZoomControlsEnabled(boolean enabled) {}
                @Override public void setMyLocationButtonEnabled(boolean enabled) {}
                @Override public void setCompassEnabled(boolean enabled) { ui.setCompassEnabled(enabled); }
            };
        }

        @Override
        public void setOnCameraIdleListener(Runnable callback) {
            this.onCameraIdleListener = callback;
        }

        @Override
        public void setOnCameraMoveStartedListener(OnCameraMoveStartedListener listener) {
            this.onCameraMoveStartedListener = listener;
        }

        @Override
        public IMapsProvider.CameraPosition getCameraPosition() {
            org.maplibre.android.camera.CameraPosition pos = mapLibreMap.getCameraPosition();
            if (pos == null || pos.target == null) {
                return new IMapsProvider.CameraPosition(new IMapsProvider.LatLng(0, 0), 0);
            }
            return new IMapsProvider.CameraPosition(
                    new IMapsProvider.LatLng(pos.target.getLatitude(), pos.target.getLongitude()),
                    (float) pos.zoom);
        }

        @Override
        public void setOnMapLoadedCallback(Runnable callback) {
            if (callback != null) mapView.post(callback);
        }

        @Override
        public IProjection getProjection() {
            return latLng -> {
                PointF pf = mapLibreMap.getProjection().toScreenLocation(
                        new org.maplibre.android.geometry.LatLng(latLng.latitude, latLng.longitude));
                return new Point((int) pf.x, (int) pf.y);
            };
        }

        @Override
        public void setPadding(int left, int top, int right, int bottom) {}

        @Override
        public void setMapStyle(IMapStyleOptions style) {
            nightMode = (style != null);
            mapLibreMap.setStyle(getStyleUrl(), s -> reAddMarkersAfterStyleChange());
        }

        @Override
        public IMarker addMarker(IMarkerOptions markerOptions) {
            MapLibreMarkerOptions opts = (MapLibreMarkerOptions) markerOptions;
            MarkerOptions mo = buildNativeOptions(opts);
            Marker nativeMarker = mapLibreMap.addMarker(mo);
            Marker[] holder = {nativeMarker};
            IMarker iMarker = createIMarker(mo, holder);
            markerOptionsList.add(mo);
            markerHolders.add(holder);
            iMarkerList.add(iMarker);
            return iMarker;
        }

        private MarkerOptions buildNativeOptions(MapLibreMarkerOptions opts) {
            MarkerOptions mo = new MarkerOptions();
            if (opts.position != null) {
                mo.position(new org.maplibre.android.geometry.LatLng(opts.position.latitude, opts.position.longitude));
            }
            if (opts.title != null) mo.title(opts.title);
            if (opts.snippet != null) mo.snippet(opts.snippet);
            if (opts.icon != null) {
                mo.icon(IconFactory.getInstance(mapView.getContext()).fromBitmap(opts.icon));
            }
            return mo;
        }

        private IMarker createIMarker(MarkerOptions mo, Marker[] holder) {
            return new IMarker() {
                Object tag;
                @Override public Object getTag() { return tag; }
                @Override public void setTag(Object t) { tag = t; }
                @Override public IMapsProvider.LatLng getPosition() {
                    org.maplibre.android.geometry.LatLng p = holder[0].getPosition();
                    return new IMapsProvider.LatLng(p.getLatitude(), p.getLongitude());
                }
                @Override public void setPosition(IMapsProvider.LatLng latLng) {
                    org.maplibre.android.geometry.LatLng ll = new org.maplibre.android.geometry.LatLng(latLng.latitude, latLng.longitude);
                    mo.position(ll);
                    holder[0].setPosition(ll);
                }
                @Override public void setRotation(int rotation) {}
                @Override public void setIcon(Bitmap bitmap) {
                    Icon icon = IconFactory.getInstance(mapView.getContext()).fromBitmap(bitmap);
                    mo.icon(icon);
                    holder[0].setIcon(icon);
                }
                @Override public void setIcon(int resId) {}
                @Override public void remove() {
                    int idx = markerHolders.indexOf(holder);
                    if (idx >= 0) {
                        markerOptionsList.remove(idx);
                        markerHolders.remove(idx);
                        iMarkerList.remove(idx);
                    }
                    mapLibreMap.removeAnnotation(holder[0]);
                }
            };
        }

        @Override
        public ICircle addCircle(ICircleOptions circleOptions) {
            MapLibreCircleOptions opts = (MapLibreCircleOptions) circleOptions;
            PolygonOptions po = new PolygonOptions();
            po.fillColor(opts.fillColor);
            po.strokeColor(opts.strokeColor);
            if (opts.center != null) {
                po.addAll(buildCirclePoints(opts.center, opts.radius));
            }
            Polygon polygon = mapLibreMap.addPolygon(po);
            final double[] radiusHolder = {opts.radius};
            final IMapsProvider.LatLng[] centerHolder = {opts.center};
            return new ICircle() {
                @Override public void setStrokeColor(int color) {}
                @Override public void setFillColor(int color) {}
                @Override public void setRadius(double r) {
                    radiusHolder[0] = r;
                    if (centerHolder[0] != null) {
                        polygon.setPoints(buildCirclePoints(centerHolder[0], r));
                    }
                }
                @Override public double getRadius() { return radiusHolder[0]; }
                @Override public void setCenter(IMapsProvider.LatLng latLng) {
                    centerHolder[0] = latLng;
                    polygon.setPoints(buildCirclePoints(latLng, radiusHolder[0]));
                }
                @Override public void remove() {
                    mapLibreMap.removeAnnotation(polygon);
                }
            };
        }

        private List<org.maplibre.android.geometry.LatLng> buildCirclePoints(IMapsProvider.LatLng center, double radiusMeters) {
            List<org.maplibre.android.geometry.LatLng> points = new ArrayList<>();
            int segments = 64;
            for (int i = 0; i < segments; i++) {
                double angle = Math.toRadians(i * 360.0 / segments);
                double dx = radiusMeters * Math.cos(angle);
                double dy = radiusMeters * Math.sin(angle);
                double dLat = dy / 111320.0;
                double dLng = dx / (111320.0 * Math.cos(Math.toRadians(center.latitude)));
                points.add(new org.maplibre.android.geometry.LatLng(center.latitude + dLat, center.longitude + dLng));
            }
            return points;
        }

        @Override
        public void setOnMyLocationChangeListener(Consumer<Location> callback) {
            myLocationChangeListener = callback;
        }

        @Override
        public void setOnMarkerClickListener(OnMarkerClickListener listener) {
            pendingMarkerClickListener = listener;
            mapLibreMap.setOnMarkerClickListener(marker -> {
                if (listener != null) {
                    // Find the IMarker wrapper for this native marker via holder identity.
                    for (int i = 0; i < markerHolders.size(); i++) {
                        if (markerHolders.get(i)[0].equals(marker)) {
                            return listener.onClick(iMarkerList.get(i));
                        }
                    }
                }
                return false;
            });
        }

        @Override
        public void setOnCameraMoveListener(Runnable callback) {
            this.onCameraMoveListener = callback;
        }
    }

    // --- IMapView implementation ---

    static class InterceptableMapView extends MapView {
        private ITouchInterceptor dispatchInterceptor;
        private ITouchInterceptor interceptInterceptor;

        InterceptableMapView(Context context) {
            super(context);
        }

        void setDispatchInterceptor(ITouchInterceptor interceptor) {
            this.dispatchInterceptor = interceptor;
        }

        void setInterceptInterceptor(ITouchInterceptor interceptor) {
            this.interceptInterceptor = interceptor;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (dispatchInterceptor != null) {
                return dispatchInterceptor.onInterceptTouchEvent(ev, super::dispatchTouchEvent);
            }
            return super.dispatchTouchEvent(ev);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (interceptInterceptor != null) {
                return interceptInterceptor.onInterceptTouchEvent(ev, super::onInterceptTouchEvent);
            }
            return super.onInterceptTouchEvent(ev);
        }
    }

    static class MapLibreMapView implements IMapView {
        private final InterceptableMapView mapView;

        MapLibreMapView(Context context) {
            MapLibre.getInstance(context);
            mapView = new InterceptableMapView(context);
        }

        @Override
        public View getView() {
            return mapView;
        }

        @Override
        public void getMapAsync(Consumer<IMap> callback) {
            mapView.getMapAsync(mapLibreMap ->
                mapLibreMap.setStyle(STYLE_LIBERTY, style ->
                    callback.accept(new MapLibreMapImpl(mapView, mapLibreMap))
                )
            );
        }

        @Override
        public void onResume() { mapView.onResume(); }

        @Override
        public void onPause() { mapView.onPause(); }

        @Override
        public void onCreate(Bundle savedInstance) { mapView.onCreate(savedInstance); }

        @Override
        public void onDestroy() { mapView.onDestroy(); }

        @Override
        public void onLowMemory() { mapView.onLowMemory(); }

        @Override
        public void setOnDispatchTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            mapView.setDispatchInterceptor(touchInterceptor);
        }

        @Override
        public void setOnInterceptTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            mapView.setInterceptInterceptor(touchInterceptor);
        }

        @Override
        public void setOnLayoutListener(Runnable callback) {
            mapView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> callback.run());
        }

        @Override
        public GLSurfaceView getGlSurfaceView() {
            return findGlSurfaceView(mapView);
        }

        private GLSurfaceView findGlSurfaceView(View view) {
            if (view instanceof GLSurfaceView) return (GLSurfaceView) view;
            if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    GLSurfaceView found = findGlSurfaceView(vg.getChildAt(i));
                    if (found != null) return found;
                }
            }
            return null;
        }
    }

    // --- Options builders ---

    static class MapLibreMarkerOptions implements IMarkerOptions {
        IMapsProvider.LatLng position;
        Bitmap icon;
        String title;
        String snippet;
        float anchorU = 0.5f;
        float anchorV = 1.0f;
        boolean flat;

        @Override public IMarkerOptions position(IMapsProvider.LatLng latLng) { this.position = latLng; return this; }
        @Override public IMarkerOptions icon(Bitmap bitmap) { this.icon = bitmap; return this; }
        @Override public IMarkerOptions icon(int resId) { return this; }
        @Override public IMarkerOptions anchor(float u, float v) { this.anchorU = u; this.anchorV = v; return this; }
        @Override public IMarkerOptions title(String title) { this.title = title; return this; }
        @Override public IMarkerOptions snippet(String snippet) { this.snippet = snippet; return this; }
        @Override public IMarkerOptions flat(boolean flat) { this.flat = flat; return this; }
    }

    static class MapLibreCircleOptions implements ICircleOptions {
        IMapsProvider.LatLng center;
        double radius;
        int strokeColor;
        int fillColor;
        int strokeWidth;

        @Override public ICircleOptions center(IMapsProvider.LatLng latLng) { this.center = latLng; return this; }
        @Override public ICircleOptions radius(double radius) { this.radius = radius; return this; }
        @Override public ICircleOptions strokeColor(int color) { this.strokeColor = color; return this; }
        @Override public ICircleOptions fillColor(int color) { this.fillColor = color; return this; }
        @Override public ICircleOptions strokePattern(List<PatternItem> pattern) { return this; }
        @Override public ICircleOptions strokeWidth(int width) { this.strokeWidth = width; return this; }
    }

    // --- LatLngBounds ---

    static class MapLibreLatLngBoundsBuilder implements ILatLngBoundsBuilder {
        private final LatLngBounds.Builder builder = new LatLngBounds.Builder();

        @Override
        public ILatLngBoundsBuilder include(IMapsProvider.LatLng latLng) {
            builder.include(new org.maplibre.android.geometry.LatLng(latLng.latitude, latLng.longitude));
            return this;
        }

        @Override
        public ILatLngBounds build() {
            return new MapLibreLatLngBounds(builder.build());
        }
    }

    static class MapLibreLatLngBounds implements ILatLngBounds {
        final LatLngBounds latLngBounds;

        MapLibreLatLngBounds(LatLngBounds latLngBounds) {
            this.latLngBounds = latLngBounds;
        }

        @Override
        public IMapsProvider.LatLng getCenter() {
            org.maplibre.android.geometry.LatLng center = latLngBounds.getCenter();
            return new IMapsProvider.LatLng(center.getLatitude(), center.getLongitude());
        }
    }
}
