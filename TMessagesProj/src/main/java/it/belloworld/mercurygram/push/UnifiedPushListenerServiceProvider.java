package it.belloworld.mercurygram.push;

import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.unifiedpush.android.connector.UnifiedPush;

import java.util.List;

/**
 * UnifiedPush-backed push provider plus the Simple Push (token_type=4)
 * registration helpers. Selected by ApplicationLoaderImpl.onCreatePushProvider,
 * replacing the no-op Google provider of the FOSS build.
 */
public final class UnifiedPushListenerServiceProvider implements PushListenerController.IPushListenerServiceProvider {
    public static final UnifiedPushListenerServiceProvider INSTANCE = new UnifiedPushListenerServiceProvider();

    private UnifiedPushListenerServiceProvider() {}

    @Override
    public boolean hasServices() {
        return false;
    }

    @Override
    public String getLogTitle() {
        return "UnifiedPush";
    }

    @Override
    public void onRequestPushToken() {
    }

    @Override
    public int getPushType() {
        return PUSH_TYPE_FIREBASE;
    }
}
