package it.belloworld.mercurygram.compat.gms.wallet;

import android.content.Intent;

/** Stub — Google Wallet removed in FOSS builds. */
public class PaymentData {
    public static PaymentData getFromIntent(Intent intent) { return null; }
    public String toJson() { return null; }
}
