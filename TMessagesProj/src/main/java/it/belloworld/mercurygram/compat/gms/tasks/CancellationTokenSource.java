package it.belloworld.mercurygram.compat.gms.tasks;

/** Stub: GMS removed in FOSS builds. */
public class CancellationTokenSource {
    private final CancellationToken token = new CancellationToken();

    public CancellationToken getToken() { return token; }

    public void cancel() {}
}
