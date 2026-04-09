package it.belloworld.mercurygram.compat.billing;

/** Stub: billing is disabled in FOSS builds. */
public class QueryPurchasesParams {
    public static Builder newBuilder() { return new Builder(); }

    public static class Builder {
        public Builder setProductType(String productType) { return this; }
        public QueryPurchasesParams build() { return new QueryPurchasesParams(); }
    }
}
