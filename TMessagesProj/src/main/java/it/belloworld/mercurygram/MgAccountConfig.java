package it.belloworld.mercurygram;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.TLRPC;

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
    public int defaultFolderId = 0;
    public boolean messageDetailsMenu = false;
    public boolean disableLivePhotosByDefault = false;
    public boolean savedMessagesHistory = false;
    public String transcribeLang = SharedConfig.MG_TRANSCRIBE_LANG_DEVICE;
    public boolean hideStories = false;
    public boolean hidePremiumPromo = false;
    public boolean disableGlobalSearch = false;
    public boolean disableAiEditor = false;
    public boolean disableAiSummary = false;
    public boolean disableInstantView = false;
    public boolean disableLinkPreviews = false;
    public boolean preferSecretChats = false;
    public boolean deleteForAllByDefault = false;
    public boolean stripTrackingParams = false;
    public boolean disableCloudDrafts = false;
    public boolean confirmInternalLinks = false;

    /**
     * Whether {@code draftMessage} may go to the server. An empty draft carries no
     * content, so let the clear reach the server even with cloud drafts off:
     * otherwise a draft stored before the toggle was enabled stays on the server
     * forever and gets pushed back into the composer on the next sync.
     *
     * <p>The emptiness test enumerates TL fields that change on every layer bump, so
     * it lives here rather than inline in MediaDataController.
     */
    public boolean allowsCloudSync(TLRPC.DraftMessage draftMessage) {
        if (!disableCloudDrafts) {
            return true;
        }
        return TextUtils.isEmpty(draftMessage.message)
            && (draftMessage.reply_to == null || draftMessage.reply_to.reply_to_msg_id == 0)
            && draftMessage.effect == 0
            && draftMessage.rich_message == null
            && draftMessage.suggested_post == null;
    }

    // MG: the reduced temp-key TTL ladder (1h→6h→24h) exhausted on this
    // account — server kept rejecting bindTempAuthKey, so native reduced
    // mode was force-disabled here while the global SharedConfig toggle
    // stays on (other accounts may still be running reduced mode). Surfaced
    // as a footer under Settings → Mercurygram → Privacy so the user is
    // not silently downgraded. Reset when the user re-enables the global
    // toggle (off→on cycle).
    public boolean mgReducedTrackingExhausted = false;

    public void save(SharedPreferences.Editor editor) {
        editor.putBoolean("rearRoundCamera", rearRoundCamera);
        editor.putBoolean("hideChatKeyboard", hideChatKeyboard);
        editor.putBoolean("hideAllTab", hideAllTab);
        editor.putInt("defaultFolderId", defaultFolderId);
        editor.putBoolean("messageDetailsMenu", messageDetailsMenu);
        editor.putBoolean("disableLivePhotosByDefault", disableLivePhotosByDefault);
        editor.putBoolean("savedMessagesHistory", savedMessagesHistory);
        editor.putString("transcribeLang", transcribeLang);
        editor.putBoolean("hideStories", hideStories);
        editor.putBoolean("hidePremiumPromo", hidePremiumPromo);
        editor.putBoolean("disableGlobalSearch", disableGlobalSearch);
        editor.putBoolean("disableAiEditor", disableAiEditor);
        editor.putBoolean("disableAiSummary", disableAiSummary);
        editor.putBoolean("disableInstantView", disableInstantView);
        editor.putBoolean("disableLinkPreviews", disableLinkPreviews);
        editor.putBoolean("preferSecretChats", preferSecretChats);
        editor.putBoolean("deleteForAllByDefault", deleteForAllByDefault);
        editor.putBoolean("stripTrackingParams", stripTrackingParams);
        editor.putBoolean("disableCloudDrafts", disableCloudDrafts);
        editor.putBoolean("confirmInternalLinks", confirmInternalLinks);
        editor.putBoolean("mgReducedTrackingExhausted", mgReducedTrackingExhausted);
    }

    public void load(SharedPreferences preferences) {
        rearRoundCamera = preferences.getBoolean("rearRoundCamera", false);
        hideChatKeyboard = preferences.getBoolean("hideChatKeyboard", false);
        hideAllTab = preferences.getBoolean("hideAllTab", false);
        defaultFolderId = preferences.getInt("defaultFolderId", 0);
        messageDetailsMenu = preferences.getBoolean("messageDetailsMenu", false);
        disableLivePhotosByDefault = preferences.getBoolean("disableLivePhotosByDefault", false);
        savedMessagesHistory = preferences.getBoolean("savedMessagesHistory", false);
        transcribeLang = preferences.getString("transcribeLang", SharedConfig.MG_TRANSCRIBE_LANG_DEVICE);
        hideStories = preferences.getBoolean("hideStories", false);
        hidePremiumPromo = preferences.getBoolean("hidePremiumPromo", false);
        disableGlobalSearch = preferences.getBoolean("disableGlobalSearch", false);
        disableAiEditor = preferences.getBoolean("disableAiEditor", false);
        disableAiSummary = preferences.getBoolean("disableAiSummary", false);
        disableInstantView = preferences.getBoolean("disableInstantView", false);
        disableLinkPreviews = preferences.getBoolean("disableLinkPreviews", false);
        preferSecretChats = preferences.getBoolean("preferSecretChats", false);
        deleteForAllByDefault = preferences.getBoolean("deleteForAllByDefault", false);
        stripTrackingParams = preferences.getBoolean("stripTrackingParams", false);
        disableCloudDrafts = preferences.getBoolean("disableCloudDrafts", false);
        confirmInternalLinks = preferences.getBoolean("confirmInternalLinks", false);
        mgReducedTrackingExhausted = preferences.getBoolean("mgReducedTrackingExhausted", false);
    }

    public void reset() {
        rearRoundCamera = false;
        hideChatKeyboard = false;
        hideAllTab = false;
        defaultFolderId = 0;
        messageDetailsMenu = false;
        disableLivePhotosByDefault = false;
        savedMessagesHistory = false;
        transcribeLang = SharedConfig.MG_TRANSCRIBE_LANG_DEVICE;
        hideStories = false;
        hidePremiumPromo = false;
        disableGlobalSearch = false;
        disableAiEditor = false;
        disableAiSummary = false;
        disableInstantView = false;
        disableLinkPreviews = false;
        preferSecretChats = false;
        deleteForAllByDefault = false;
        stripTrackingParams = false;
        disableCloudDrafts = false;
        confirmInternalLinks = false;
        mgReducedTrackingExhausted = false;
    }
}
