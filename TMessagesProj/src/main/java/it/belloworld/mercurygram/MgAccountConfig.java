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
    public boolean hideChatKeyboard = false;
    public boolean hideAllTab = false;
    public boolean messageDetailsMenu = false;
    public boolean disableLivePhotosByDefault = false;
    public boolean savedMessagesHistory = false;

    public void save(SharedPreferences.Editor editor) {
        editor.putBoolean("sendLargePhotos", sendLargePhotos);
        editor.putBoolean("rearRoundCamera", rearRoundCamera);
        editor.putBoolean("hideChatKeyboard", hideChatKeyboard);
        editor.putBoolean("hideAllTab", hideAllTab);
        editor.putBoolean("messageDetailsMenu", messageDetailsMenu);
        editor.putBoolean("disableLivePhotosByDefault", disableLivePhotosByDefault);
        editor.putBoolean("savedMessagesHistory", savedMessagesHistory);
    }

    public void load(SharedPreferences preferences) {
        sendLargePhotos = preferences.getBoolean("sendLargePhotos", false);
        rearRoundCamera = preferences.getBoolean("rearRoundCamera", false);
        hideChatKeyboard = preferences.getBoolean("hideChatKeyboard", false);
        hideAllTab = preferences.getBoolean("hideAllTab", false);
        messageDetailsMenu = preferences.getBoolean("messageDetailsMenu", false);
        disableLivePhotosByDefault = preferences.getBoolean("disableLivePhotosByDefault", false);
        savedMessagesHistory = preferences.getBoolean("savedMessagesHistory", false);
    }

    public void reset() {
        sendLargePhotos = false;
        rearRoundCamera = false;
        hideChatKeyboard = false;
        hideAllTab = false;
        messageDetailsMenu = false;
        disableLivePhotosByDefault = false;
        savedMessagesHistory = false;
    }
}
