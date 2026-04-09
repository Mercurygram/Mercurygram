package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import androidx.core.util.Consumer;

@SuppressLint("MissingPermission")
public class AndroidLocationProvider implements ILocationServiceProvider {

    private LocationManager locationManager;

    @Override
    public void init(Context context) {
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public ILocationRequest onCreateLocationRequest() {
        return new AndroidLocationRequest();
    }

    @Override
    public void getLastLocation(Consumer<Location> callback) {
        Location location = null;
        try {
            location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location == null) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        callback.accept(location);
    }

    @Override
    public void requestLocationUpdates(ILocationRequest request, ILocationListener locationListener) {
        try {
            AndroidLocationRequest req = (AndroidLocationRequest) request;
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    locationListener.onLocationChanged(location);
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, req.interval, 0, listener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, req.interval, 0, listener);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void removeLocationUpdates(ILocationListener locationListener) {
        // No-op: Android LocationManager doesn't support removing by custom listener wrapper here
    }

    @Override
    public void checkLocationSettings(ILocationRequest request, Consumer<Integer> callback) {
        callback.accept(STATUS_SUCCESS);
    }

    @Override
    public IMapApiClient onCreateLocationServicesAPI(Context context, IAPIConnectionCallbacks connectionCallbacks, IAPIOnConnectionFailedListener failedListener) {
        return new IMapApiClient() {
            @Override
            public void connect() {
                connectionCallbacks.onConnected(null);
            }
            @Override
            public void disconnect() {}
        };
    }

    @Override
    public boolean checkServices() {
        return false;
    }

    public static class AndroidLocationRequest implements ILocationRequest {
        long interval = 1000L;

        @Override
        public void setPriority(int priority) {}

        @Override
        public void setInterval(long interval) {
            this.interval = interval;
        }

        @Override
        public void setFastestInterval(long interval) {}
    }
}
