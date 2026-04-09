/*
 * This is the source code of Telegram for Android v. 5.x.x
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

// [TF] Google Voice removed — stub implementation
public class GoogleVoiceClientService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
