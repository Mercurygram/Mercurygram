package it.belloworld.mercurygram.compat.gms.safetynet;

import android.content.Context;

/** Stub — SafetyNet removed in FOSS builds. */
public class SafetyNet {
    public static SafetyNetClient getClient(Context context) {
        return new SafetyNetClient();
    }
}
