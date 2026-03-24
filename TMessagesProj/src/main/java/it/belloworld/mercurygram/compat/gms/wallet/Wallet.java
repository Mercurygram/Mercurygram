package it.belloworld.mercurygram.compat.gms.wallet;

import android.content.Context;

/** Stub — Google Wallet removed in FOSS builds. */
public class Wallet {

    public static PaymentsClient getPaymentsClient(Context context, WalletOptions options) {
        return new PaymentsClient();
    }

    public static class WalletOptions {
        public static class Builder {
            public Builder setEnvironment(int environment) { return this; }
            public Builder setTheme(int theme) { return this; }
            public WalletOptions build() { return new WalletOptions(); }
        }
    }
}
