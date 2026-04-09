package org.telegram.messenger;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import it.belloworld.mercurygram.compat.billing.BillingFlowParams;
import it.belloworld.mercurygram.compat.billing.BillingResult;
import it.belloworld.mercurygram.compat.billing.ProductDetails;
import it.belloworld.mercurygram.compat.billing.ProductDetailsResponseListener;
import it.belloworld.mercurygram.compat.billing.Purchase;
import it.belloworld.mercurygram.compat.billing.PurchasesResponseListener;
import it.belloworld.mercurygram.compat.billing.QueryProductDetailsParams;

import org.telegram.messenger.utils.BillingUtilities;
import org.telegram.tgnet.TLRPC;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub BillingController — Play Billing is removed in FOSS builds.
 * {@link BuildVars#useInvoiceBilling()} always returns {@code true}, so none of the
 * billing-specific code paths in calling code are ever reached.
 */
public class BillingController {
    public static final String PREMIUM_PRODUCT_ID = "telegram_premium";

    @Nullable
    public static ProductDetails PREMIUM_PRODUCT_DETAILS = null;

    public static boolean billingClientEmpty = true;

    private static BillingController instance;

    public static BillingController getInstance() {
        if (instance == null) {
            instance = new BillingController(ApplicationLoader.applicationContext);
        }
        return instance;
    }

    private final Map<String, Integer> currencyExpMap = new HashMap<>();
    private Runnable onCanceled;
    private String lastPremiumTransaction;
    private String lastPremiumToken;
    private final List<Runnable> setupListeners = new ArrayList<>();

    private BillingController(Context ctx) {}

    public void setOnCanceled(Runnable onCanceled) {
        this.onCanceled = onCanceled;
    }

    public String getLastPremiumTransaction() { return lastPremiumTransaction; }
    public String getLastPremiumToken() { return lastPremiumToken; }

    public String formatCurrency(long amount, String currency) {
        return formatCurrency(amount, currency, getCurrencyExp(currency));
    }

    public String formatCurrency(long amount, String currency, int exp) {
        return formatCurrency(amount, currency, exp, false);
    }

    private static NumberFormat currencyInstance;
    private static NumberFormat currencyInstanceRounded;
    public String formatCurrency(long amount, String currency, int exp, boolean rounded) {
        if (currency == null || currency.isEmpty()) {
            return String.valueOf(amount);
        }
        if ("TON".equalsIgnoreCase(currency)) {
            return "TON " + (amount / 1_000_000_000.0);
        }
        if ("XTR".equalsIgnoreCase(currency)) {
            return "XTR " + LocaleController.formatNumber(amount, ',');
        }
        Currency cur = Currency.getInstance(currency);
        if (cur != null) {
            if (currencyInstance == null) {
                currencyInstance = NumberFormat.getCurrencyInstance();
            }
            currencyInstance.setCurrency(cur);
            if (rounded) {
                if (currencyInstanceRounded == null) {
                    currencyInstanceRounded = NumberFormat.getCurrencyInstance();
                }
                currencyInstanceRounded.setCurrency(cur);
                currencyInstanceRounded.setMaximumFractionDigits(0);
                currencyInstanceRounded.setMinimumFractionDigits(0);
                return currencyInstanceRounded.format(Math.round(amount / Math.pow(10, exp)));
            }
            final int defaultFractionDigits = cur.getDefaultFractionDigits();
            currencyInstance.setMinimumFractionDigits(defaultFractionDigits);
            currencyInstance.setMaximumFractionDigits(defaultFractionDigits);
            return currencyInstance.format(amount / Math.pow(10, exp));
        }
        return amount + " " + currency;
    }

    @SuppressWarnings("ConstantConditions")
    public int getCurrencyExp(String currency) {
        BillingUtilities.extractCurrencyExp(currencyExpMap);
        return currencyExpMap.getOrDefault(currency, 0);
    }

    public boolean isReady() { return false; }
    public void startConnection() {}

    public void queryProductDetails(List<QueryProductDetailsParams.Product> products, ProductDetailsResponseListener listener) {}
    public void queryPurchases(String productType, PurchasesResponseListener listener) {}

    public boolean startManageSubscription(Context ctx, String productId) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                String.format("https://play.google.com/store/account/subscriptions?sku=%s&package=%s",
                    productId, ctx.getPackageName()))));
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    public void addResultListener(String productId, Consumer<BillingResult> listener) {}

    public void launchBillingFlow(Activity activity, AccountInstance accountInstance,
            TLRPC.InputStorePaymentPurpose paymentPurpose,
            List<BillingFlowParams.ProductDetailsParams> productDetails) {}

    public void launchBillingFlow(Activity activity, AccountInstance accountInstance,
            TLRPC.InputStorePaymentPurpose paymentPurpose,
            List<BillingFlowParams.ProductDetailsParams> productDetails,
            BillingFlowParams.SubscriptionUpdateParams subscriptionUpdateParams,
            boolean checkedConsume) {}

    public void consumeGiftPurchase(Purchase purchase, TLRPC.InputStorePaymentPurpose purpose, Runnable onDone) {
        if (onDone != null) onDone.run();
    }

    public void whenSetuped(Runnable listener) {
        setupListeners.add(listener);
    }

    public static String getResponseCodeString(int code) {
        switch (code) {
            case -3: return "SERVICE_TIMEOUT";
            case -2: return "FEATURE_NOT_SUPPORTED";
            case -1: return "SERVICE_DISCONNECTED";
            case  0: return "OK";
            case  1: return "USER_CANCELED";
            case  2: return "SERVICE_UNAVAILABLE";
            case  3: return "BILLING_UNAVAILABLE";
            case  4: return "ITEM_UNAVAILABLE";
            case  5: return "DEVELOPER_ERROR";
            case  6: return "ERROR";
            case  7: return "ITEM_ALREADY_OWNED";
            case  8: return "ITEM_NOT_OWNED";
            case 12: return "NETWORK_ERROR";
        }
        return "BILLING_UNKNOWN_ERROR";
    }
}
