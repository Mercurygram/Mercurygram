# Notifications

Since [Android 8.0 Oreo, Google doesn't allow apps to run in the background anymore](https://developer.android.com/about/versions/oreo/background#services), requiring all apps which were previously keeping a background connection to exclusively use Firebase push messaging.

Mercurygram can't use Google's push messaging in a FOSS app. The preferred replacement is [UnifiedPush](https://unifiedpush.org) — install a distributor, or simply disable battery optimization for Mercurygram. If you can use neither, the **Keep-Alive Service** falls back to holding a background connection open, but Android then requires the app to show an ongoing notification; otherwise the OS kills the service and you wouldn't be notified about new messages.

Sadly, if the app set the notification to lower priority (to hide it a bit in the lower part of the notification screen), you would immediately get a system notification about Mercurygram "using battery", which is confusing and is the reason for this not being the default. Despite Google's misleading warnings, there is no real difference in battery usage.

The Keep-Alive Service only delivers messages together with the **Background Connection** setting — enable both if you rely on this fallback.

## Make it better

You may still lower the priority of the notification channel or even hide it altogether manually (make a long tap on the notification). You will then receive the misleading system notification, which [may be disabled as well with another long tap](https://9to5google.com/2017/10/26/how-to-disable-android-oreo-using-battery-notification-android-basics/).
