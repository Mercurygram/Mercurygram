package org.telegram.messenger;

import android.content.Context;

import androidx.annotation.Nullable;

import org.telegram.tgnet.TLRPC;

import java.util.Collections;
import java.util.List;

import it.belloworld.mercurygram.compat.firebase.VerificationSupportResult;
import it.belloworld.mercurygram.compat.gms.tasks.CancellationToken;
import it.belloworld.mercurygram.compat.gms.tasks.Task;

/**
 * Stub: Firebase Phone Number Verification is removed in FOSS builds and
 * BuildVars.SUPPORTS_FPNV is false, so login falls back to the code flow.
 */
public class FirebasePhoneNumberVerificationHelper {

    private FirebasePhoneNumberVerificationHelper() {

    }

    public static class SimInfo {
        public int slot;
        public String carrierName;
        public String countryName;
        public String mccMnc;
        public String phoneNumber;
    }

    public static SimInfo getSimInfo(Context context, VerificationSupportResult result) {
        return new SimInfo();
    }

    public static void getVerificationSupportInfo(Utilities.Callback<List<VerificationSupportResult>> callback) {
        callback.run(Collections.emptyList());
    }

    public static Task<TLRPC.auth_Authorization> loginViaFirebasePhoneNumberVerification(
        Context context,
        int currentAccount,
        CancellationToken cancellationToken,
        @Nullable Runnable onVerifying
    ) {
        return new Task<TLRPC.auth_Authorization>() {};
    }
}
