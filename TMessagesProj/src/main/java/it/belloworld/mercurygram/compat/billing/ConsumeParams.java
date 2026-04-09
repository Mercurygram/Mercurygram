package it.belloworld.mercurygram.compat.billing;

/** Stub: billing is disabled in FOSS builds. */
public class ConsumeParams {
    public static Builder newBuilder() { return new Builder(); }

    public static class Builder {
        public Builder setPurchaseToken(String token) { return this; }
        public ConsumeParams build() { return new ConsumeParams(); }
    }
}
