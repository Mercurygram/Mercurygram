package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected ILocationServiceProvider onCreateLocationServiceProvider() {
        return new AndroidLocationProvider();
    }

    @Override
    protected IMapsProvider onCreateMapsProvider() {
        return new MapLibreMapsProvider();
    }

    @Override
    protected PushListenerController.IPushListenerServiceProvider onCreatePushProvider() {
        return it.belloworld.mercurygram.push.UnifiedPushListenerServiceProvider.INSTANCE;
    }

    @Override
    protected boolean isStandalone() {
        return true;
    }

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }
}
