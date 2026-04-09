package org.telegram.messenger.utils;

import android.content.Context;
import android.util.Base64;

import androidx.annotation.OptIn;
import androidx.core.util.Pair;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.base.Charsets;

import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_wallet;

import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

/** Billing utilities — real billing code removed for FOSS builds. */
@OptIn(markerClass = UnstableApi.class)
public class BillingUtilities {
    private static final String CURRENCY_FILE = "currencies.json";
    private static final String CURRENCY_EXP = "exp";

    @SuppressWarnings("ConstantConditions")
    public static void extractCurrencyExp(Map<String, Integer> currencyExpMap) {
        if (!currencyExpMap.isEmpty()) {
            return;
        }
        try {
            Context ctx = ApplicationLoader.applicationContext;
            InputStream in = ctx.getAssets().open(CURRENCY_FILE);
            JSONObject obj = new JSONObject(new String(Util.toByteArray(in), Charsets.UTF_8));
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject currency = obj.optJSONObject(key);
                if (currency != null) {
                    currencyExpMap.put(key, currency.optInt(CURRENCY_EXP));
                }
            }
            in.close();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void fillTitles(TL_wallet.currencyRates rates) {
        if (rates == null || rates.rates == null) {
            return;
        }
        try (InputStream in = ApplicationLoader.applicationContext.getAssets().open(CURRENCY_FILE)) {
            JSONObject currencies = new JSONObject(new String(Util.toByteArray(in), Charsets.UTF_8));
            for (TL_wallet.currencyRate rate : rates.rates) {
                if (rate == null) {
                    continue;
                }
                JSONObject currency = currencies.optJSONObject(rate.currency);
                if (currency == null) {
                    rate.title = rate.currency;
                    rate.symbol = rate.currency;
                    rate.thousandsSeparator = ",";
                    rate.decimalSeparator = ".";
                    rate.spaceBetween = true;
                    rate.exp = 2;
                    continue;
                }

                rate.title = currency.optString("title", rate.currency);
                String nativeSymbol = currency.optString("native", rate.currency);
                String internationalSymbol = currency.optString("symbol", rate.currency);
                boolean useInternationalSymbol =
                        ("$".equals(nativeSymbol) && !"USD".equals(rate.currency)) ||
                        "kr".equals(nativeSymbol);
                rate.symbol = (
                    "AED".equals(rate.currency) ? "\u20C3" :
                    "GEL".equals(rate.currency) ? "₾" :
                    useInternationalSymbol ? internationalSymbol : nativeSymbol
                );
                rate.thousandsSeparator = currency.optString("thousands_sep", ",");
                rate.decimalSeparator = currency.optString("decimal_sep", ".");
                rate.symbolLeft = currency.optBoolean("symbol_left");
                rate.spaceBetween = currency.optBoolean("space_between");
                rate.dropZeros = currency.optBoolean("drop_zeros");
                rate.exp = currency.optInt(CURRENCY_EXP, 2);
                if ("AED".equals(rate.currency)) {
                    rate.title = rate.title.replace("United Arab Emirates", "UAE");
                } else if ("USD".equalsIgnoreCase(rate.currency)) {
                    rate.title = rate.title.replace("United States", "US");
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static Pair<String, String> createDeveloperPayload(TLRPC.InputStorePaymentPurpose paymentPurpose, AccountInstance accountInstance) {
        long currentAccountId = accountInstance.getUserConfig().getClientUserId();
        byte[] currentAccountIdBytes = String.valueOf(currentAccountId).getBytes(Charsets.UTF_8);
        String obfuscatedAccountId = Base64.encodeToString(currentAccountIdBytes, Base64.DEFAULT);

        SerializedData serializedData = new SerializedData(paymentPurpose.getObjectSize());
        paymentPurpose.serializeToStream(serializedData);
        String obfuscatedData = Base64.encodeToString(serializedData.toByteArray(), Base64.DEFAULT);
        serializedData.cleanup();
        return Pair.create(obfuscatedAccountId, obfuscatedData);
    }

    private static AccountInstance findAccountById(long accountId) {
        AccountInstance result = null;
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            AccountInstance acc = AccountInstance.getInstance(i);
            if (acc.getUserConfig().getClientUserId() == accountId) {
                result = acc;
                break;
            }
        }
        return result;
    }
}
