package it.belloworld.mercurygram.compat.billing;

import java.util.List;

/** Stub: billing is disabled in FOSS builds. */
public class ProductDetails {

    public String getProductId() { return null; }
    public OneTimePurchaseOfferDetails getOneTimePurchaseOfferDetails() { return null; }
    public List<SubscriptionOfferDetails> getSubscriptionOfferDetails() { return null; }

    public static class OneTimePurchaseOfferDetails {
        public long getPriceAmountMicros() { return 0L; }
        public String getPriceCurrencyCode() { return null; }
        public String getFormattedPrice() { return null; }
    }

    public static class SubscriptionOfferDetails {
        public PricingPhases getPricingPhases() { return null; }
        public String getOfferToken() { return null; }
    }

    public static class PricingPhases {
        public List<PricingPhase> getPricingPhaseList() { return null; }
    }

    public static class PricingPhase {
        public String getPriceCurrencyCode() { return null; }
        public long getPriceAmountMicros() { return 0L; }
        public String getBillingPeriod() { return null; }
        public String getFormattedPrice() { return null; }
    }
}
