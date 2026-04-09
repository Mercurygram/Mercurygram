package it.belloworld.mercurygram.compat.billing;

/** Stub: billing is disabled in FOSS builds. */
public class BillingClient {

    public static class BillingResponseCode {
        public static final int OK = 0;
        public static final int USER_CANCELED = 1;
        public static final int SERVICE_UNAVAILABLE = 2;
        public static final int BILLING_UNAVAILABLE = 3;
        public static final int ITEM_UNAVAILABLE = 4;
        public static final int DEVELOPER_ERROR = 5;
        public static final int ERROR = 6;
        public static final int ITEM_ALREADY_OWNED = 7;
        public static final int ITEM_NOT_OWNED = 8;
        public static final int SERVICE_DISCONNECTED = -1;
        public static final int FEATURE_NOT_SUPPORTED = -2;
        public static final int SERVICE_TIMEOUT = -3;
        public static final int NETWORK_ERROR = 12;
    }

    public static class ProductType {
        public static final String INAPP = "inapp";
        public static final String SUBS = "subs";
    }

    public boolean isReady() { return false; }
}
