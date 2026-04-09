package it.belloworld.mercurygram.compat.billing;

import java.util.List;

/** Stub: billing is disabled in FOSS builds. */
public interface PurchasesResponseListener {
    void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> purchases);
}
