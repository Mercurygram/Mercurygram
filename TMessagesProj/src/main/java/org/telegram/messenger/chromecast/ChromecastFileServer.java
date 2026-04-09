package org.telegram.messenger.chromecast;

/** Stub ChromecastFileServer — Cast framework removed in FOSS builds. */
public class ChromecastFileServer {

    public static final ChromecastMedia ASSET_FALLBACK_FILE = null;

    public static String getHost() { return ""; }

    public static String getUrlToSource(String host, String path) {
        return host + path;
    }

    public void addFileToCast(ChromecastMedia media) {}
    public void removeFileFromCast(ChromecastMedia media) {}
    public void setCoverFile(String path, java.io.File file) {}
    public java.io.File getCoverFile() { return null; }
    public String getCoverPath() { return null; }
}
