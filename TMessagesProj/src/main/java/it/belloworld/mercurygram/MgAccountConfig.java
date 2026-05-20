package it.belloworld.mercurygram;

import android.content.SharedPreferences;

import org.telegram.messenger.SharedConfig;

/**
 * Mercurygram per-account settings. Lives in the same per-account
 * userconfig / userconfig&lt;N&gt; SharedPreferences file as the rest of
 * {@link org.telegram.messenger.UserConfig} (pref keys unchanged, no
 * migration); UserConfig owns one instance and calls save/load/reset.
 */
public class MgAccountConfig {

    public boolean sendLargePhotos = false;
    public boolean rearRoundCamera = false;

    public void save(SharedPreferences.Editor editor) {
        editor.putBoolean("sendLargePhotos", sendLargePhotos);
        editor.putBoolean("rearRoundCamera", rearRoundCamera);
    }

    public void load(SharedPreferences preferences) {
        sendLargePhotos = preferences.getBoolean("sendLargePhotos", false);
        rearRoundCamera = preferences.getBoolean("rearRoundCamera", false);
    }

    public void reset() {
        sendLargePhotos = false;
        rearRoundCamera = false;
    }
}
