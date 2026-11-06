package it.belloworld.mercurygram.emoji;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.TextPaint;

import org.telegram.messenger.CompoundEmoji;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.EmojiData;
import org.telegram.messenger.FileLog;

/**
 * Mercurygram: draw emoji with the phone's own emoji font.
 *
 * Each glyph is rasterized once into a square bitmap and handed to the regular
 * emoji bitmap cache, so every surface that draws emoji (chat text, keyboard,
 * reactions, suggestions, big emoji-only messages) switches together and the
 * span and sizing logic stays untouched. A glyph the system font lacks (an
 * older font and a newer sequence) returns null so the caller falls back to
 * the bundled set instead of drawing a tofu box.
 */
public final class MgSystemEmoji {

    private MgSystemEmoji() {}

    private static final TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    // Fixed reference glyph that sets one text size and baseline for the whole
    // set, so narrow or short emoji keep their natural proportions instead of
    // each being stretched to fill the square.
    private static final String REFERENCE = "😀";
    private static int size;
    private static float baseline;

    public static Bitmap render(int page, int page2) {
        try {
            String text = presentationForm(EmojiData.data[page][page2]);
            synchronized (paint) {
                if (size == 0) {
                    setup();
                }
                // fixEmoji only restores U+FE0F for a short list of BMP code
                // points, so a lone text-default one such as U+00A9 stays bare
                // and resolves to the first font that covers it (Roboto), a
                // black text glyph. Ask for the emoji form when the font has it.
                if (text.length() == Character.charCount(text.codePointAt(0))
                        && paint.hasGlyph(text + "\uFE0F")) {
                    text += "\uFE0F";
                } else if (!paint.hasGlyph(text)) {
                    return null;
                }
                Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                float x = (size - paint.measureText(text)) / 2f;
                new Canvas(bitmap).drawText(text, x, baseline, paint);
                return bitmap;
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void setup() {
        // Emoji.bigImgSize, so emoji-only messages stay sharp. Not larger: the
        // cache never evicts, and every glyph costs size * size * 4 bytes.
        size = Emoji.bigImgSize;
        paint.setTextSize(size);
        Rect ref = new Rect();
        paint.getTextBounds(REFERENCE, 0, REFERENCE.length(), ref);
        float scale = size * 0.94f / Math.max(1, Math.max(ref.width(), ref.height()));
        paint.setTextSize(size * scale);
        paint.getTextBounds(REFERENCE, 0, REFERENCE.length(), ref);
        baseline = size / 2f - (ref.top + ref.bottom) / 2f;
    }

    // EmojiData stores sequences without the U+FE0F presentation selector.
    // Without it the font fallback may pick a monochrome text glyph (e.g. for
    // U+263A), so restore it the same way outgoing text does; fixEmoji skips
    // keycaps, which need it before U+20E3. A skin-tone modifier sequence takes
    // no U+FE0F, but fixEmoji adds one after U+261D and U+270C even when a tone
    // follows, which can break the ligature, so drop it there.
    private static String presentationForm(String code) {
        int keycap = code.indexOf('\u20E3');
        if (keycap > 0 && code.charAt(keycap - 1) != '\uFE0F') {
            return code.substring(0, keycap) + "\uFE0F" + code.substring(keycap);
        }
        String fixed = Emoji.fixEmoji(code);
        for (String tone : CompoundEmoji.skinTones) {
            fixed = fixed.replace("\uFE0F" + tone, tone);
        }
        return fixed;
    }
}
