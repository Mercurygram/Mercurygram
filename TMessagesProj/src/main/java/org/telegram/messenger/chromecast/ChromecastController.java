package org.telegram.messenger.chromecast;

import java.io.File;

/** Stub ChromecastController — Cast framework removed in FOSS builds. */
public class ChromecastController {

    private static volatile ChromecastController Instance = null;

    public static ChromecastController getInstance() {
        if (Instance == null) {
            synchronized (ChromecastController.class) {
                if (Instance == null) {
                    Instance = new ChromecastController();
                }
            }
        }
        return Instance;
    }

    private ChromecastController() {}

    public boolean isCasting() { return false; }

    public void setCurrentMediaAndCastIfNeeded(ChromecastMediaVariations media) {}

    public String setCover(File file) { return null; }

    public boolean isPlaying(ChromecastMediaVariations media) { return false; }
}
