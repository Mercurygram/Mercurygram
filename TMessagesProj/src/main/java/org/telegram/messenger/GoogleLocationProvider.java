package org.telegram.messenger;

import android.content.Context;
import android.location.Location;

import androidx.core.util.Consumer;

/** Stub GoogleLocationProvider — FusedLocation removed in FOSS builds. Uses AndroidLocationProvider instead. */
public class GoogleLocationProvider implements ILocationServiceProvider {

    @Override
    public void init(Context context) {}

    @Override
    public ILocationRequest onCreateLocationRequest() {
        // LocationController's constructor calls the setters unconditionally,
        // so this must never return null.
        return new ILocationRequest() {
            @Override
            public void setPriority(int priority) {}

            @Override
            public void setInterval(long interval) {}

            @Override
            public void setFastestInterval(long interval) {}
        };
    }

    @Override
    public IMapApiClient onCreateLocationServicesAPI(Context context, IAPIConnectionCallbacks connectionCallbacks, IAPIOnConnectionFailedListener failedListener) { return null; }

    @Override
    public boolean checkServices() { return false; }

    @Override
    public void getLastLocation(Consumer<Location> callback) {}

    @Override
    public void requestLocationUpdates(ILocationRequest request, ILocationListener locationListener) {}

    @Override
    public void removeLocationUpdates(ILocationListener locationListener) {}

    @Override
    public void checkLocationSettings(ILocationRequest request, Consumer<Integer> callback) {
        callback.accept(STATUS_SETTINGS_CHANGE_UNAVAILABLE);
    }
}
