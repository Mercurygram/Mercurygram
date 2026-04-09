package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;

/**
 * Stub CaptchaController — reCAPTCHA is removed in FOSS builds.
 * Requests immediately fail with a RECAPTCHA_FAILED token so the server
 * can fall back to other verification methods.
 */
public class CaptchaController {

    public static class Request {
        public int currentAccount;
        public String action;
        public String key_id;

        public Request(int currentAccount, String action, String key_id) {
            this.currentAccount = currentAccount;
            this.action = action;
            this.key_id = key_id;
        }

        @Override
        public int hashCode() {
            return Objects.hash(currentAccount, action, key_id);
        }

        public HashSet<Integer> requestTokens = new HashSet<>();

        public void done(String token) {
            currentRequests.remove(this.hashCode());
            int[] tokens = new int[this.requestTokens.size()];
            int i = 0;
            for (Integer t : this.requestTokens) {
                tokens[i++] = t;
            }
            ConnectionsManager.getInstance(currentAccount)
                .native_receivedCaptchaResult(currentAccount, tokens, token);
        }
    }

    public static HashMap<Integer, Request> currentRequests;

    public static void request(int currentAccount, int requestToken, String action, String key_id) {
        if (currentRequests == null) {
            currentRequests = new HashMap<>();
        }
        final int key = Objects.hash(currentAccount, action, key_id);
        Request r = currentRequests.get(key);
        if (r != null) {
            r.requestTokens.add(requestToken);
            return;
        }
        r = new Request(currentAccount, action, key_id);
        r.requestTokens.add(requestToken);
        // reCAPTCHA removed — immediately report failure
        r.done("RECAPTCHA_FAILED_FOSS_BUILD");
    }
}
