package it.belloworld.mercurygram.compat.gms.safetynet;

import it.belloworld.mercurygram.compat.gms.tasks.Task;

/** Stub — SafetyNet removed in FOSS builds. */
public class SafetyNetClient {
    public Task<SafetyNetResponse> attest(byte[] nonce, String apiKey) {
        return new Task<SafetyNetResponse>() {};
    }
}
