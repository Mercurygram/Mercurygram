package it.belloworld.mercurygram.translate;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.davidv.translator.ErrorType;
import dev.davidv.translator.ITranslationCallback;
import dev.davidv.translator.ITranslationService;
import dev.davidv.translator.TranslationError;

/**
 * Bound-service client for {@code dev.davidv.translator}'s AIDL
 * {@link ITranslationService}. Each translate call is dispatched
 * silently to the provider's background service — no Activity is
 * brought to the foreground — so the same code path serves both the
 * modal Translate alert AND per-chat auto-translate batches.
 *
 * Plain-text round-trip: Bergamot returns no MessageEntities, so
 * bold/italic/code/spoiler/blockquote/custom-emoji formatting is
 * dropped on the offline path. A one-shot toast warns the user the
 * first time.
 *
 * Picked over a bundled engine to keep MG free of native ML code,
 * model downloads, and the F-Droid reproducibility risks they carry;
 * mirrors the UnifiedPush delegation pattern.
 */
public final class MgAidlTranslate {

    private MgAidlTranslate() {}

    public static final String PROVIDER_PACKAGE = "dev.davidv.translator";
    private static final String PROVIDER_ACTION = "dev.davidv.translator.ITranslationService";

    // Bounded pending queue — under sustained bind failure we drop with
    // fast failure rather than retain captured texts indefinitely.
    private static final int PENDING_CAP = 64;

    /**
     * MG-local failure categories. Decoupled from the AIDL
     * {@link ErrorType} mirror so a provider-side enum rename only
     * touches the mapping switch in {@link #onTranslationError}, and
     * we can model non-AIDL failures (bind died, queue full, etc.)
     * with the same vocabulary.
     */
    public enum Reason {
        PROVIDER_UNAVAILABLE,    // bind failed, null/died binding, queue full, empty input, service==null, RemoteException
        LANGUAGE_NOT_DETECTED,   // ErrorType.COULD_NOT_DETECT_LANGUAGE
        MODEL_NOT_INSTALLED,     // ErrorType.DETECTED_BUT_UNAVAILABLE (carry the language string)
        UNEXPECTED,              // ErrorType.UNEXPECTED + Throwable
    }

    /** Immutable failure descriptor. {@code null} return == success. */
    public static final class Failure {
        public final Reason reason;
        @Nullable public final String language;
        public Failure(Reason reason, @Nullable String language) {
            this.reason = reason;
            this.language = language;
        }
        public static Failure of(Reason reason) { return new Failure(reason, null); }
    }

    private static ITranslationService service;
    private static IBinder serviceBinder;
    private static boolean binding;
    private static final Deque<Runnable> pending = new ArrayDeque<>();
    private static final ArrayList<InflightCb> inflight = new ArrayList<>();

    /** Once-firing wrapper for the user-facing callback. */
    private static final class InflightCb {
        final AtomicBoolean fired = new AtomicBoolean(false);
        final Utilities.Callback3<String, Boolean, Failure> done;
        InflightCb(Utilities.Callback3<String, Boolean, Failure> done) { this.done = done; }
        void fire(@Nullable String result, boolean rateLimit, @Nullable Failure failure) {
            if (!fired.compareAndSet(false, true)) return;
            synchronized (MgAidlTranslate.class) {
                inflight.remove(this);
            }
            try { done.run(result, rateLimit, failure); } catch (Throwable t) { FileLog.e(t); }
        }
    }

    private static final IBinder.DeathRecipient deathRecipient = MgAidlTranslate::handleProviderLost;

    private static final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Deque<Runnable> drain;
            synchronized (MgAidlTranslate.class) {
                service = ITranslationService.Stub.asInterface(binder);
                serviceBinder = binder;
                binding = false;
                try {
                    binder.linkToDeath(deathRecipient, 0);
                } catch (RemoteException e) {
                    FileLog.e(e);
                }
                drain = new ArrayDeque<>(pending);
                pending.clear();
            }
            for (Runnable r : drain) {
                try { r.run(); } catch (Throwable t) { FileLog.e(t); }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Provider lost; the binder DeathRecipient drains in-flight
            // callbacks when the remote process actually dies. Just null
            // the local refs so the next translate() rebinds cleanly.
            synchronized (MgAidlTranslate.class) {
                service = null;
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                try { ctx.unbindService(this); } catch (Throwable ignored) {}
            }
            handleProviderLost();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                try { ctx.unbindService(this); } catch (Throwable ignored) {}
            }
            handleProviderLost();
        }
    };

    private static void handleProviderLost() {
        ArrayList<InflightCb> inflightDrain;
        Deque<Runnable> pendingDrain;
        synchronized (MgAidlTranslate.class) {
            if (serviceBinder != null) {
                try { serviceBinder.unlinkToDeath(deathRecipient, 0); } catch (Throwable ignored) {}
                serviceBinder = null;
            }
            service = null;
            binding = false;
            inflightDrain = new ArrayList<>(inflight);
            inflight.clear();
            pendingDrain = new ArrayDeque<>(pending);
            pending.clear();
        }
        // Pending runnables call doTranslate which sees service==null and
        // fires PROVIDER_UNAVAILABLE. Running them drains the queue cleanly.
        for (Runnable r : pendingDrain) {
            try { r.run(); } catch (Throwable t) { FileLog.e(t); }
        }
        for (InflightCb cb : inflightDrain) {
            cb.fire(null, false, Failure.of(Reason.PROVIDER_UNAVAILABLE));
        }
    }

    /** True when the provider is installed AND exposes the AIDL service intent. */
    public static boolean isUsable(Context ctx) {
        if (ctx == null) ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return false;
        if (!isProviderInstalled(ctx)) return false;
        Intent probe = new Intent(PROVIDER_ACTION).setPackage(PROVIDER_PACKAGE);
        try {
            return ctx.getPackageManager().resolveService(probe, 0) != null;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    public static boolean isProviderInstalled(Context ctx) {
        if (ctx == null) ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return false;
        try {
            ctx.getPackageManager().getApplicationInfo(PROVIDER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    public static String getFdroidInstallUrl() {
        return "https://f-droid.org/packages/" + PROVIDER_PACKAGE + "/";
    }

    /**
     * Fires an AIDL translation request and routes the result to
     * {@code done.run(text, rateLimit, failure)}. {@code text != null}
     * means success; {@code failure != null} means an AIDL/binder
     * failure that the dispatcher can map onto a localized bulletin.
     * The success and failure branches are mutually exclusive; on
     * success {@code failure} is null, on failure {@code text} is null.
     */
    public static void translate(String text, String toLang, Utilities.Callback3<String, Boolean, Failure> done) {
        if (done == null) return;
        if (TextUtils.isEmpty(text)) {
            done.run(null, false, Failure.of(Reason.PROVIDER_UNAVAILABLE));
            return;
        }
        final Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            done.run(null, false, Failure.of(Reason.PROVIDER_UNAVAILABLE));
            return;
        }

        final Runnable dispatch = () -> doTranslate(text, toLang, done);

        boolean queueFull = false;
        boolean dispatchNow = false;
        boolean startBind = false;
        synchronized (MgAidlTranslate.class) {
            if (service != null) {
                dispatchNow = true;
            } else if (pending.size() >= PENDING_CAP) {
                queueFull = true;
            } else {
                pending.add(dispatch);
                if (!binding) {
                    binding = true;
                    startBind = true;
                }
            }
        }

        if (queueFull) {
            done.run(null, false, Failure.of(Reason.PROVIDER_UNAVAILABLE));
            return;
        }
        if (dispatchNow) {
            dispatch.run();
            return;
        }
        if (startBind) {
            Intent intent = new Intent(PROVIDER_ACTION).setPackage(PROVIDER_PACKAGE);
            boolean started;
            try {
                started = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            } catch (SecurityException e) {
                FileLog.e(e);
                started = false;
            }
            if (!started) {
                Deque<Runnable> drain;
                synchronized (MgAidlTranslate.class) {
                    binding = false;
                    drain = new ArrayDeque<>(pending);
                    pending.clear();
                }
                try { ctx.unbindService(connection); } catch (Throwable ignored) {}
                for (Runnable r : drain) {
                    try { r.run(); } catch (Throwable t) { FileLog.e(t); }
                }
            }
        }
        // else: bind already in flight — onServiceConnected /
        // onBindingDied / onNullBinding will drain the dispatch.
    }

    private static void doTranslate(String text, String toLang, Utilities.Callback3<String, Boolean, Failure> done) {
        // Hop off the UI thread before the binder call: translate() is
        // not declared oneway and may block the calling thread for the
        // length of the provider's stub.
        Utilities.globalQueue.postRunnable(() -> {
            ITranslationService svc;
            synchronized (MgAidlTranslate.class) {
                svc = service;
            }
            if (svc == null) {
                done.run(null, false, Failure.of(Reason.PROVIDER_UNAVAILABLE));
                return;
            }
            final InflightCb cb = new InflightCb(done);
            synchronized (MgAidlTranslate.class) {
                inflight.add(cb);
            }
            try {
                svc.translate(text, null, toLang, new ITranslationCallback.Stub() {
                    @Override
                    public void onTranslationResult(String translatedText) {
                        if (TextUtils.isEmpty(translatedText)) {
                            cb.fire(null, false, Failure.of(Reason.UNEXPECTED));
                        } else {
                            cb.fire(translatedText, false, null);
                        }
                    }

                    @Override
                    public void onTranslationError(TranslationError error) {
                        if (error != null) {
                            FileLog.e("MgAidlTranslate error: type=" + error.type
                                    + " language=" + error.language
                                    + " message=" + error.message);
                        }
                        cb.fire(null, false, mapErrorType(error));
                    }
                });
            } catch (RemoteException e) {
                FileLog.e(e);
                cb.fire(null, false, Failure.of(Reason.PROVIDER_UNAVAILABLE));
            } catch (Throwable t) {
                FileLog.e(t);
                cb.fire(null, false, Failure.of(Reason.UNEXPECTED));
            }
        });
    }

    private static Failure mapErrorType(@Nullable TranslationError error) {
        // AIDL @JavaPassthrough generates ErrorType as int constants, not a
        // Java enum — switch on the bare int value.
        if (error == null) return Failure.of(Reason.UNEXPECTED);
        switch (error.type) {
            case ErrorType.COULD_NOT_DETECT_LANGUAGE:
                return Failure.of(Reason.LANGUAGE_NOT_DETECTED);
            case ErrorType.DETECTED_BUT_UNAVAILABLE:
                return new Failure(Reason.MODEL_NOT_INSTALLED, error.language);
            case ErrorType.UNEXPECTED:
            default:
                return Failure.of(Reason.UNEXPECTED);
        }
    }

    public static boolean shouldShowFormatToast() {
        return SharedConfig.MG_TRANSLATE_MODE_OFFLINE.equals(SharedConfig.mg_translateMode)
                && !SharedConfig.mg_translateOfflineFormatToastShown;
    }

    public static String formatToastText() {
        return LocaleController.getString(R.string.MercurygramTranslateOfflineFormatToast);
    }
}
