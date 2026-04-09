package org.telegram.messenger.chromecast;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.AssetDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.FileDataSource;

/** Stub ChromecastFileServer — Cast framework removed in FOSS builds. */
@OptIn(markerClass = UnstableApi.class)
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
