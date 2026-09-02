package it.belloworld.mercurygram;

import android.content.Context;
import android.os.SystemClock;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

/**
 * Surfaces a server FLOOD_WAIT on the generic connection. tgnet re-queues the
 * request for N seconds and never calls the Java callback, so without this the
 * only symptom is "Updating..." that does not clear and a message that stays
 * pending. One toast per flood-wait window, selected account only.
 */
public final class MgFloodWaitNotice {

    private static final int MIN_WAIT_SECONDS = 5;
    private static long mutedUntil = 0L;

    private MgFloodWaitNotice() {}

    public static void show(int account, int waitTime) {
        if (waitTime < MIN_WAIT_SECONDS || account != UserConfig.selectedAccount) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            long now = SystemClock.elapsedRealtime();
            if (now < mutedUntil) {
                return;
            }
            mutedUntil = now + waitTime * 1000L;
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return;
            }
            try {
                Toast.makeText(ctx, LocaleController.formatString(
                        "MercurygramFloodWaitToast", R.string.MercurygramFloodWaitToast,
                        LocaleController.formatDuration(waitTime)), Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }
}
