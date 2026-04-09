package org.telegram.ui.Components.Paint;

import android.graphics.Bitmap;
import org.telegram.ui.Components.Size;
import org.telegram.ui.Components.Point;

/** Stub — GMS Vision face detection removed in FOSS builds. */
public class PhotoFace {

    private float width;
    private float angle;

    private Point foreheadPoint;
    private Point eyesCenterPoint;
    private float eyesDistance;
    private Point mouthPoint;
    private Point chinPoint;

    public PhotoFace(Object face, Bitmap sourceBitmap, Size targetSize, boolean sideward) {
    }

    public boolean isSufficient() {
        return eyesCenterPoint != null;
    }

public Point getPointForAnchor(int anchor) {
        switch (anchor) {
            case 0: {
                return foreheadPoint;
            }

            case 1: {
                return eyesCenterPoint;
            }

            case 2: {
                return mouthPoint;
            }

            case 3: {
                return chinPoint;
            }

            default: {
                return null;
            }
        }
    }

    public float getWidthForAnchor(int anchor) {
        if (anchor == 1) {
            return eyesDistance;
        }
        return width;
    }

    public float getAngle() {
        return angle;
    }
 }
