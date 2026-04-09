package org.telegram.messenger.chromecast;

import android.net.Uri;

/** Stub ChromecastMedia — Cast framework removed in FOSS builds. */
public class ChromecastMedia {
    public static final String IMAGE_JPEG = "image/jpeg";
    public static final String IMAGE_PNG = "image/png";
    public static final String VIDEO_MP4 = "video/mp4";
    public static final String APPLICATION_X_MPEG_URL = "application/x-mpegURL";

    public final String mimeType;
    public final Uri internalUri;
    public final String externalPath;
    public final int width;
    public final int height;

    private ChromecastMedia(Builder b) {
        this.mimeType = b.mimeType;
        this.internalUri = b.internalUri;
        this.externalPath = b.externalPath;
        this.width = b.width;
        this.height = b.height;
    }

    public static class Builder {
        private final String mimeType;
        private final Uri internalUri;
        private final String externalPath;
        private int width, height;

        private Builder(String mime, Uri uri, String path) {
            this.mimeType = mime;
            this.internalUri = uri;
            this.externalPath = path;
        }

        public static Builder fromUri(Uri uri, String externalPath, String mimeType) {
            return new Builder(mimeType, uri, externalPath);
        }

        public Builder setTitle(String title) { return this; }
        public Builder setSubtitle(String subtitle) { return this; }
        public Builder setSize(int w, int h) { this.width = w; this.height = h; return this; }
        public Builder setMetadata(Object metadata) { return this; }

        public ChromecastMedia build() { return new ChromecastMedia(this); }
    }
}
