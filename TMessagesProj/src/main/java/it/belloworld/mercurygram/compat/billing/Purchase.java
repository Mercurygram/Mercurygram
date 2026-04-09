package it.belloworld.mercurygram.compat.billing;

import java.util.List;

/** Stub: billing is disabled in FOSS builds. */
public class Purchase {

    public static class PurchaseState {
        public static final int PURCHASED = 1;
        public static final int PENDING = 2;
        public static final int UNSPECIFIED_STATE = 0;
    }

    public String getPurchaseToken() { return null; }
    public List<String> getProducts() { return null; }
    public int getPurchaseState() { return PurchaseState.UNSPECIFIED_STATE; }
    public boolean isAcknowledged() { return false; }
    public String getOrderId() { return null; }
    public String getOriginalJson() { return null; }
    public AccountIdentifiers getAccountIdentifiers() { return null; }
}
