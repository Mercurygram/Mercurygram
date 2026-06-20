package it.belloworld.mercurygram;

import org.telegram.messenger.UserConfig;

/**
 * Mercurygram: central predicate for the per-account "disable link previews"
 * privacy toggle (issue #26).
 *
 * <p>Telegram fires {@code account.getWebPagePreview} the moment a URL appears
 * in a compose field, so its servers fetch the link before the message is even
 * sent. Every compose surface that would issue that RPC — the chat message
 * field, the share dialog, the poll editor, bot share, and the story-link
 * sheet — consults {@link #suppressed(int)} before sending, so a pasted URL is
 * never handed to the server for preview while the user is composing.
 *
 * <p>Deliberately NOT consulted on non-composing paths: opening a link
 * ({@code Browser}) and reloading previews on already-received messages
 * ({@code MessagesController.reloadWebPages}). Those render existing content
 * rather than leaking a freshly typed URL, and the toggle is scoped to
 * composing.
 */
public final class MgLinkPreview {

    private MgLinkPreview() {
    }

    /** True when account {@code account} has link-preview fetching disabled. */
    public static boolean suppressed(int account) {
        return UserConfig.getInstance(account).mg.disableLinkPreviews;
    }
}
