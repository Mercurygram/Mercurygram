package it.belloworld.mercurygram;

import android.graphics.Typeface;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;

/**
 * System-typeface substitution for the bundled fonts, used when the
 * "use system font" toggle is on. Extracted from AndroidUtilities.
 */
public final class MgSystemFont {

    private MgSystemFont() {}

    public static Typeface typefaceFor(String assetPath) {
        switch (assetPath) {
            case AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM:
                return Typeface.create("sans-serif-medium", Typeface.NORMAL);
            case AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM_ITALIC:
                return Typeface.create("sans-serif-medium", Typeface.ITALIC);
            case AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    return Typeface.create(Typeface.DEFAULT, 800, false);
                }
                return Typeface.DEFAULT_BOLD;
            case AndroidUtilities.TYPEFACE_ROBOTO_MONO:
                return Typeface.MONOSPACE;
            case AndroidUtilities.TYPEFACE_MERRIWEATHER_BOLD:
                return Typeface.create("serif", Typeface.BOLD);
            case "fonts/ritalic.ttf":
                return Typeface.create("sans-serif", Typeface.ITALIC);
            case "fonts/rcondensedbold.ttf":
                return Typeface.create("sans-serif-condensed", Typeface.BOLD);
        }
        return null;
    }
}
