package it.belloworld.mercurygram.compat.billing;

/** Stub: billing is disabled in FOSS builds. */
public class BillingFlowParams {

    public static class SubscriptionUpdateParams {
        public static Builder newBuilder() { return new Builder(); }

        public static class Builder {
            public Builder setOldPurchaseToken(String token) { return this; }
            public Builder setSubscriptionReplacementMode(int mode) { return this; }
            public SubscriptionUpdateParams build() { return new SubscriptionUpdateParams(); }
        }

        public static class ReplacementMode {
            public static final int CHARGE_FULL_PRICE = 5;
        }
    }

    public static class ProductDetailsParams {
        public static Builder newBuilder() { return new Builder(); }

        public static class Builder {
            public Builder setProductDetails(ProductDetails productDetails) { return this; }
            public Builder setOfferToken(String offerToken) { return this; }
            public ProductDetailsParams build() { return new ProductDetailsParams(); }
        }
    }
}
