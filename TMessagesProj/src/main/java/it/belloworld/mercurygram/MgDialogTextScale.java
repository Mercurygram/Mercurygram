package it.belloworld.mercurygram;

import static org.telegram.messenger.AndroidUtilities.dp;

import org.telegram.messenger.ApplicationLoader;

/**
 * Makes the chat list text follow the system font size
 * (Settings > Display > Font size). DialogCell sizes its paints with dp(),
 * which ignores Configuration.fontScale, so the chat list stayed small
 * while every other app grew.
 *
 * The scale is capped at 1.3: past that the name, time and badges of a row
 * start to collide. At the default font size every value here is a no-op.
 * A font size change recreates the activity (fontScale is not listed in the
 * manifest configChanges), so reading the scale on each layout is enough.
 */
public final class MgDialogTextScale {

    private static final float MAX_SCALE = 1.3f;

    private MgDialogTextScale() {}

    public static float scale() {
        float scale = ApplicationLoader.applicationContext.getResources().getConfiguration().fontScale;
        return Math.max(1f, Math.min(scale, MAX_SCALE));
    }

    /** Text size in px for a chat list paint sized {@code size} dp at scale 1. */
    public static int text(float size) {
        return dp(size * scale());
    }

    /** How far the lines below the name move down (name paint is 16dp). */
    public static int nameExtra() {
        return extra(16);
    }

    /** How far the message moves down below the sender name line (14dp). */
    public static int senderExtra() {
        return extra(14);
    }

    /** Extra row height: name (16) + message (15), plus sender name (14) in three lines. */
    public static int rowExtra(boolean threeLines) {
        return extra(16 + 15 + (threeLines ? 14 : 0));
    }

    /** Extra px a line of text sized {@code size} dp at scale 1 takes at the current scale. */
    private static int extra(float size) {
        return dp(size * (scale() - 1f));
    }
}
