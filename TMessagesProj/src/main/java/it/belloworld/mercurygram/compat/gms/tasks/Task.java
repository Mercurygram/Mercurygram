package it.belloworld.mercurygram.compat.gms.tasks;

import java.util.function.Consumer;

/** Stub Task — GMS removed in FOSS builds. */
public abstract class Task<TResult> {

    public Task<TResult> addOnSuccessListener(Consumer<TResult> listener) { return this; }

    public Task<TResult> addOnFailureListener(Consumer<Exception> listener) { return this; }

    public Task<TResult> addOnCompleteListener(Consumer<Task<TResult>> listener) { return this; }

    public Task<TResult> addOnCompleteListener(Object activity, Consumer<Task<TResult>> listener) { return this; }

    public boolean isSuccessful() { return false; }

    public TResult getResult() { return null; }

    public <X extends Throwable> TResult getResult(Class<X> exceptionType) throws X { return null; }

    public Exception getException() { return null; }
}
