package it.belloworld.mercurygram.compat.billing;

import java.util.List;

/** Stub: billing is disabled in FOSS builds. */
public class QueryProductDetailsParams {

    public static Builder newBuilder() { return new Builder(); }

    public static class Builder {
        public Builder setProductList(List<Product> products) { return this; }
        public QueryProductDetailsParams build() { return new QueryProductDetailsParams(); }
    }

    public static class Product {
        public static Builder newBuilder() { return new Builder(); }
        public String getProductId() { return null; }

        public static class Builder {
            public Builder setProductType(String productType) { return this; }
            public Builder setProductId(String productId) { return this; }
            public Product build() { return new Product(); }
        }
    }
}
