package it.belloworld.mercurygram.compat.integrity;

/** Stub — Play Integrity removed in FOSS builds. */
public class IntegrityTokenRequest {

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        public Builder setNonce(String nonce) { return this; }
        public Builder setCloudProjectNumber(long projectId) { return this; }
        public IntegrityTokenRequest build() { return new IntegrityTokenRequest(); }
    }
}
