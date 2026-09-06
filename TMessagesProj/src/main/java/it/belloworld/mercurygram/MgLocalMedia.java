package it.belloworld.mercurygram;

import android.text.TextUtils;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;

import java.io.File;

/**
 * Finds the local file behind a message's media, including a cached video
 * quality variant.
 *
 * Videos with server-side quality variants are streamed from one of the
 * variants, not from the original document, so after playback the only file
 * on disk is the variant's. Upstream's share paths only look for the original
 * and either report "download first" or share a dead URI. Save to gallery
 * already falls back to the cached variant; this mirrors that chain.
 *
 * Cheapest lookups first: the cache-dir guess is a plain string join, while
 * the plain path lookup and the variant sweep block on the file database.
 */
public class MgLocalMedia {

    private MgLocalMedia() {
    }

    /** An existing file for the message media, or null when nothing is on disk yet. */
    public static File cachedFile(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return null;
        }
        FileLoader fileLoader = FileLoader.getInstance(message.currentAccount);
        if (!TextUtils.isEmpty(message.messageOwner.attachPath)) {
            File f = new File(message.messageOwner.attachPath);
            if (f.exists()) {
                return f;
            }
        }
        File f = fileLoader.getPathToMessage(message.messageOwner, true, true);
        if (f.exists()) {
            return f;
        }
        f = fileLoader.getPathToMessage(message.messageOwner);
        if (f.exists()) {
            return f;
        }
        if (message.qualityToSave != null) {
            f = fileLoader.getPathToAttach(message.qualityToSave, null, false, true);
            if (f.exists()) {
                return f;
            }
        }
        message.updateQualitiesCached(true);
        if (message.cachedQuality != null && message.cachedQuality.isCached()) {
            f = new File(message.cachedQuality.uri.getPath());
            if (f.exists()) {
                return f;
            }
        }
        return null;
    }
}
