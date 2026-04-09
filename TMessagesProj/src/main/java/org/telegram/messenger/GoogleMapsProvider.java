package org.telegram.messenger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.location.Location;
import android.view.View;

import androidx.core.util.Consumer;

import java.util.List;

/** Stub GoogleMapsProvider — Google Maps removed in FOSS builds. Uses OsmdroidMapsProvider instead. */
public class GoogleMapsProvider implements IMapsProvider {

    @Override
    public void initializeMaps(Context context) {}

    @Override
    public IMapView onCreateMapView(Context context) { return null; }

    @Override
    public IMarkerOptions onCreateMarkerOptions() { return null; }

    @Override
    public ICircleOptions onCreateCircleOptions() { return null; }

    @Override
    public ILatLngBoundsBuilder onCreateLatLngBoundsBuilder() { return null; }

    @Override
    public ICameraUpdate newCameraUpdateLatLng(LatLng latLng) { return null; }

    @Override
    public ICameraUpdate newCameraUpdateLatLngZoom(LatLng latLng, float zoom) { return null; }

    @Override
    public ICameraUpdate newCameraUpdateLatLngBounds(ILatLngBounds bounds, int padding) { return null; }

    @Override
    public IMapStyleOptions loadRawResourceStyle(Context context, int resId) { return null; }

    @Override
    public String getMapsAppPackageName() { return null; }

    @Override
    public int getInstallMapsString() { return 0; }
}
