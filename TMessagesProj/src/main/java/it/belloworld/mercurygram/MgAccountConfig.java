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

    public boolean rearRoundCamera = false;
    public boolean hideChatKeyboard = false;
    public boolean hideAllTab = false;
    public boolean messageDetailsMenu = false;

    public void save(SharedPreferences.Editor editor) {
        editor.putBoolean("rearRoundCamera", rearRoundCamera);
        editor.putBoolean("hideChatKeyboard", hideChatKeyboard);
        editor.putBoolean("hideAllTab", hideAllTab);
        editor.putBoolean("messageDetailsMenu", messageDetailsMenu);
    }

    public void load(SharedPreferences preferences) {
        rearRoundCamera = preferences.getBoolean("rearRoundCamera", false);
        hideChatKeyboard = preferences.getBoolean("hideChatKeyboard", false);
        hideAllTab = preferences.getBoolean("hideAllTab", false);
        messageDetailsMenu = preferences.getBoolean("messageDetailsMenu", false);
    }

    public void reset() {
        rearRoundCamera = false;
        hideChatKeyboard = false;
        hideAllTab = false;
        messageDetailsMenu = false;
    }
}
