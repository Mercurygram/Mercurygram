package it.belloworld.mercurygram.compat.integrity;

import android.content.Context;

/** Stub — Play Integrity removed in FOSS builds. */
public class IntegrityManagerFactory {
    public static IntegrityManager create(Context context) {
        return new IntegrityManager();
    }
}
