package it.belloworld.mercurygram.compat.integrity;

import it.belloworld.mercurygram.compat.gms.tasks.Task;

/** Stub — Play Integrity removed in FOSS builds. */
public class IntegrityManager {
    public Task<IntegrityTokenResponse> requestIntegrityToken(IntegrityTokenRequest request) {
        return new Task<IntegrityTokenResponse>() {};
    }
}
