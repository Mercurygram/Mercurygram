package org.telegram.ui;

/** Stub CastSync — Cast framework removed in FOSS builds. Always inactive. */
public class CastSync {

    public static final int TYPE_PHOTOVIEWER = 0;
    public static final int TYPE_MUSIC = 1;

    public static void check(int type) {}

    public static boolean isActive() { return false; }

    public static boolean isUpdatePending() { return false; }

    public static boolean isPlaying() { return false; }

    public static long getPosition() { return -1; }

    public static float getSpeed() { return 1.0f; }

    public static float getVolume() { return 0.5f; }

    public static void setPlaying(boolean playing) {}

    public static void stop() {}

    public static void seekTo(long ms) {}

    public static void syncPosition(long ms) {}

    public static void setSpeed(float speed) {}

    public static void setVolume(float volume) {}

    public static void syncInterface() {}

    public static void doSyncVolume(boolean sync) {}

    public static float getDeviceVolume() { return 0.5f; }
}
