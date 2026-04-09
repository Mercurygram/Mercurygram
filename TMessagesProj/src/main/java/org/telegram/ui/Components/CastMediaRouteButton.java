package org.telegram.ui.Components;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

// Stub: Chromecast (Cast framework) removed. castAvailable is always false
// so this button is never shown; it just needs to compile.
public class CastMediaRouteButton extends View {

    public CastMediaRouteButton(@NonNull Context context) {
        super(context);
    }

    public boolean isConnected() {
        return false;
    }

    public void stateUpdated(boolean connected) {
    }
}
