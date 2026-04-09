package it.belloworld.mercurygram.compat.gms.wearable;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * Stub WearableListenerService — GMS Wearable removed in FOSS builds.
 * Never bound (no GMS Wear transport on the device), so onMessageReceived is dead code.
 */
public class WearableListenerService extends Service {

    public void onMessageReceived(MessageEvent event) { }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
