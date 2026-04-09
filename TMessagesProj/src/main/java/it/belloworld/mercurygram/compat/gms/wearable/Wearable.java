package it.belloworld.mercurygram.compat.gms.wearable;

import android.content.Context;

/** Stub Wearable — GMS Wearable removed in FOSS builds. */
public final class Wearable {

    private Wearable() { }

    public static MessageClient getMessageClient(Context context) {
        return new MessageClient();
    }
}
