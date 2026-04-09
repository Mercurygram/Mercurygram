package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;

/**
 * Stub CaptchaController: reCAPTCHA is removed in FOSS builds.
 * Requests immediately fail with a RECAPTCHA_FAILED token so the server
 * can fall back to other verification methods.
 */
public class CaptchaController {

    public static void request(int currentAccount, int requestToken, int generation, String action, String key_id) {
        ConnectionsManager.native_receivedCaptchaResult(currentAccount, requestToken, generation, "RECAPTCHA_FAILED_FOSS_BUILD");
    }
}
