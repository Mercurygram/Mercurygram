package it.belloworld.mercurygram;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

public class MgUpdateChecker {

    private static final String GITHUB_LATEST_URL = "https://api.github.com/repos/Mercurygram/Mercurygram/releases/latest";
    private static final String GITHUB_LIST_URL = "https://api.github.com/repos/Mercurygram/Mercurygram/releases";
    private static final String GITHUB_TAG_URL_PREFIX = "https://api.github.com/repos/Mercurygram/Mercurygram/releases/tags/";
    private static final String MG_CERT_SHA256 = "1E73DE100E2646BE671AFAD2CB4BB471538E062A745AE5ADBE6C7D1666FD1EE9";
    private static final long CHECK_INTERVAL = 3600 * 1000; // 1 hour
    // Tighter throttle while a pending update is staged: a newer tag
    // landing 5+ minutes after the previous check should not stay hidden
    // behind the full 1-hour gate.
    private static final long CHECK_INTERVAL_PENDING = 5 * 60 * 1000;

    private static Boolean isFdroidBuildCached = null;
    private static volatile String cachedInstallVersion;
    private static final AtomicBoolean isDownloading = new AtomicBoolean(false);
    private static final AtomicBoolean isDownloadingPlugin = new AtomicBoolean(false);
    private static volatile HttpURLConnection currentDownloadConn;

    public interface ProgressCallback {
        void onProgress(long downloaded, long total);
        void onComplete(File apkFile);
        void onError(String error);
    }

    public static boolean isBetaChannel() {
        return ApplicationLoader.applicationContext.getPackageName().endsWith(".beta");
    }

    // versionName is set to the build's GitHub tag verbatim (see
    // gradle/mg-version.gradle), so PackageInfo.versionName carries the
    // real 5-dotted tag for sideloads as well as in-app-updater installs.
    static String derivePrecedingStableTag(String tag) {
        if (tag == null) return null;
        String[] p = tag.split("\\.", -1);
        if (p.length != 5) return null;
        // X.Y.Z.0.K is the pre-stable namespace — no 4-dotted X.Y.Z.0 tag
        // ever exists, so there's nothing to downgrade to.
        if ("0".equals(p[3])) return null;
        return p[0] + "." + p[1] + "." + p[2] + "." + p[3];
    }

    public static boolean acceptPreReleases() {
        return isBetaChannel() || SharedConfig.acceptPreReleaseUpdates;
    }

    // True iff the user has deliberately regressed off the prerelease channel:
    // currently on a 4-dotted stable `cur`, and `lastPre` (the last prerelease
    // tag this install ran, tracked by checkInternal) is strictly newer. Used
    // to auto-clear SharedConfig.acceptPreReleaseUpdates so the updater stops
    // re-offering the prerelease the user just left.
    //
    // "Strictly newer" (not "any prerelease seen") matters at both ends:
    // - lastPre empty -> false: a fresh opt-in that has never run a
    //   prerelease must not be cleared.
    // - cur >= lastPre -> false: a pre-stable X.Y.Z.0.K graduating to its
    //   first stable X.Y.Z.1 is a normal upgrade, not a regress, and should
    //   keep the opt-in so the user stays on the beta channel.
    static boolean shouldClearOptInOnRegress(String cur, String lastPre) {
        if (cur == null || isFiveDotted(cur) || TextUtils.isEmpty(lastPre)) return false;
        long[] c = toVersionVector(cur);
        long[] l = toVersionVector(lastPre);
        return c != null && l != null && compareVectors(c, l) < 0;
    }

    private static boolean isFiveDotted(String tag) {
        return tag != null && tag.split("\\.", -1).length >= 5;
    }

    public static boolean isFdroidBuild() {
        if (isFdroidBuildCached != null) return isFdroidBuildCached;
        try {
            isFdroidBuildCached = !matchesInstalledMgCert();
        } catch (Exception e) {
            FileLog.e(e);
            isFdroidBuildCached = true; // fail-safe: disable updater
        }
        return isFdroidBuildCached;
    }

    private static boolean matchesInstalledMgCert() throws Exception {
        PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
        String pkg = ApplicationLoader.applicationContext.getPackageName();
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            sigs = pi.signingInfo.getApkContentsSigners();
        } else {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            sigs = pi.signatures;
        }
        return matchesMgCert(sigs);
    }

    private static boolean matchesMgCert(Signature[] sigs) {
        if (sigs == null || sigs.length == 0) return false;
        String sha256 = sha256Hex(sigs[0].toByteArray());
        return MG_CERT_SHA256.equalsIgnoreCase(sha256);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static void checkForUpdates(boolean force) {
        checkInternal(force, null);
    }

    // Rolls back from a 5-dotted Release-flavor beta (X.Y.Z.M.K, M >= 1) to
    // the X.Y.Z.M stable; both share MG_VC so PackageInstaller accepts it.
    // Returns false without doing anything on pre-stable installs
    // (derivePrecedingStableTag → null): X.Y.Z.0.K has no stable to fall back
    // to, so the caller must not promise the user a download that never comes.
    public static boolean checkForDowngradeToStable() {
        String targetTag = derivePrecedingStableTag(currentInstallVersion());
        if (targetTag == null) return false;
        checkInternal(true, targetTag);
        return true;
    }

    // Sets the prerelease channel opt-in. mgLastPreReleaseTag always moves
    // with it, recording whatever is installed right now, and that pairing is
    // what makes both directions stick: an opt-out marks the running
    // prerelease as already seen so the adoption below cannot undo it, and an
    // opt-in on a stable drops the tag of an earlier stint so
    // shouldClearOptInOnRegress() cannot self-clear it.
    public static void setPreReleaseOptIn(boolean value) {
        String cur = currentInstallVersion();
        SharedConfig.setAcceptPreReleaseUpdates(value);
        SharedConfig.setMgLastPreReleaseTag(isFiveDotted(cur) ? cur : "");
    }

    // Keeps SharedConfig.acceptPreReleaseUpdates the single source of truth for
    // the channel: adopts the flag when a new prerelease is running, and clears
    // it when it detects a deliberate regress off one (see
    // shouldClearOptInOnRegress()). Called at the start of every
    // checkInternal() so a stale opt-in stops re-offering the prerelease the
    // user already left, without any dedicated UI action from the user.
    private static void maybeAutoClearPreReleaseOptIn() {
        String cur = currentInstallVersion();
        if (isFiveDotted(cur)) {
            // Running a prerelease IS the opt-in, whatever brought it here
            // (the toggle, a sideload, Obtainium). Once per tag, so a
            // deliberate toggle-off is not flipped straight back on.
            if (!cur.equals(SharedConfig.mgLastPreReleaseTag)) setPreReleaseOptIn(true);
        } else if (SharedConfig.acceptPreReleaseUpdates
                && shouldClearOptInOnRegress(cur, SharedConfig.mgLastPreReleaseTag)) {
            setPreReleaseOptIn(false);
        }
    }

    private static void checkInternal(boolean force, String pinnedTag) {
        if (isFdroidBuild()) return;

        // Local reconciliation, no network: ahead of the auto-update and
        // throttle gates so the channel flag tracks the running install at
        // once instead of lagging a CHECK_INTERVAL behind it.
        maybeAutoClearPreReleaseOptIn();

        if (!force && SharedConfig.disableAutoUpdate) return;

        long interval = SharedConfig.mgPendingUpdate != null ? CHECK_INTERVAL_PENDING : CHECK_INTERVAL;
        if (!force && Math.abs(System.currentTimeMillis() - SharedConfig.mgLastUpdateCheckTime) < interval) {
            return;
        }

        final boolean beta = isBetaChannel();
        final boolean listReleases = beta || acceptPreReleases();
        final String url = pinnedTag != null
                ? GITHUB_TAG_URL_PREFIX + pinnedTag
                : (listReleases ? GITHUB_LIST_URL : GITHUB_LATEST_URL);

        Utilities.globalQueue.postRunnable(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    return;
                }

                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    bos.write(buf, 0, len);
                }
                is.close();
                conn.disconnect();
                String body = bos.toString(StandardCharsets.UTF_8.name());

                JSONObject release;
                if (pinnedTag != null) {
                    release = new JSONObject(body);
                } else if (listReleases) {
                    // GitHub orders /releases by created_at desc, not by version
                    // or published_at — scan the whole list and keep the
                    // highest-version eligible release instead of the first.
                    JSONArray releases = new JSONArray(body);
                    JSONObject best = null;
                    long[] bestVec = null;
                    for (int i = 0; i < releases.length(); i++) {
                        JSONObject r = releases.getJSONObject(i);
                        if (r.optBoolean("draft", false)) continue;
                        if (beta && !r.optBoolean("prerelease", false)) continue;
                        long[] vec = toVersionVector(r.optString("tag_name", ""));
                        if (vec == null) continue;
                        if (bestVec == null || compareVectors(vec, bestVec) > 0) {
                            bestVec = vec;
                            best = r;
                        }
                    }
                    if (best == null) {
                        SharedConfig.mgLastUpdateCheckTime = System.currentTimeMillis();
                        SharedConfig.saveConfig();
                        return;
                    }
                    release = best;
                } else {
                    release = new JSONObject(body);
                }
                String tagName = release.getString("tag_name");

                // Downgrade path (pinnedTag != null) intentionally skips the
                // up-to-date check — current (5-dotted) > target (4-dotted of
                // same base), so versionUpToDate would short-circuit.
                if (pinnedTag == null && versionUpToDate(currentInstallVersion(), tagName)) {
                    SharedConfig.mgLastUpdateCheckTime = System.currentTimeMillis();
                    SharedConfig.saveConfig();
                    return;
                }

                JSONArray assets = release.getJSONArray("assets");
                if (Build.SUPPORTED_ABIS.length == 0) return;
                String targetAbi = Build.SUPPORTED_ABIS[0];
                String apkFileName = null;
                String downloadUrl = null;
                long fileSize = 0;

                // Beta channel publishes both Release (no infix, stable package)
                // and Debug (-debug infix, .beta package) APKs per push. A
                // .beta-installed runtime fetches the debug variant; stable
                // installs use /releases/latest (set above), which excludes
                // prereleases, so the empty-infix lookup never matches a beta
                // tag accidentally.
                String infix = ApplicationLoader.applicationContext.getPackageName()
                        .endsWith(".beta") ? "-debug" : "";
                String abiApkName = "Mercurygram" + infix + "-" + tagName + "-" + targetAbi + ".apk";
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.getString("name");
                    if (name.equals(abiApkName)) {
                        apkFileName = name;
                        downloadUrl = asset.getString("browser_download_url");
                        fileSize = asset.getLong("size");
                        break;
                    }
                }

                if (downloadUrl == null) return;

                MgUpdateInfo info = new MgUpdateInfo();
                info.versionName = tagName;
                info.downloadUrl = downloadUrl;
                info.fileSize = fileSize;
                info.changelog = release.optString("body", "");
                info.tagName = tagName;
                info.apkFileName = apkFileName;

                MgUpdateInfo prev = SharedConfig.getMgPendingUpdate();
                String prevTag = prev != null ? prev.tagName : null;

                SharedConfig.mgLastUpdateCheckTime = System.currentTimeMillis();
                SharedConfig.setMgPendingUpdate(info);

                // Suppress dialog when the same tag is already pending or when
                // the user dismissed this tag. The side-menu strip still
                // updates via the appUpdateAvailable notification.
                final boolean shouldPopup = !info.tagName.equals(prevTag)
                        && !info.tagName.equals(SharedConfig.mgDismissedPendingTag);
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                    if (!shouldPopup) return;
                    try {
                        org.telegram.ui.LaunchActivity la = org.telegram.ui.LaunchActivity.instance;
                        if (la != null) {
                            ApplicationLoader.applicationLoaderInstance.showUpdateAppPopup(la, null, 0);
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                });
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public static void downloadUpdate(MgUpdateInfo info, ProgressCallback callback) {
        if (!isDownloading.compareAndSet(false, true)) {
            return;
        }

        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection conn = null;
            File apkFile = null;
            try {
                File cacheDir = new File(ApplicationLoader.getFilesDirFixed(), "cache");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                apkFile = new File(cacheDir, "mg_update.apk");

                conn = (HttpURLConnection) new URL(info.downloadUrl).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);
                currentDownloadConn = conn;

                if (!isDownloading.get()) {
                    throw new java.io.IOException("cancelled");
                }

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    AndroidUtilities.runOnUIThread(() -> callback.onError("Download failed: HTTP " + responseCode));
                    return;
                }

                long total = conn.getContentLength();
                if (total <= 0) total = info.fileSize;

                InputStream is = new BufferedInputStream(conn.getInputStream());
                FileOutputStream fos = new FileOutputStream(apkFile);
                byte[] buf = new byte[8192];
                long downloaded = 0;
                int len;
                final long totalFinal = total;

                while ((len = is.read(buf)) != -1) {
                    if (!isDownloading.get()) {
                        throw new java.io.IOException("cancelled");
                    }
                    fos.write(buf, 0, len);
                    downloaded += len;
                    final long dl = downloaded;
                    AndroidUtilities.runOnUIThread(() -> callback.onProgress(dl, totalFinal));
                }

                fos.close();
                is.close();

                if (!verifyApkSignature(apkFile)) {
                    apkFile.delete();
                    AndroidUtilities.runOnUIThread(() -> callback.onError("Signature verification failed"));
                    return;
                }

                SharedConfig.mgUpdateApkPath = apkFile.getAbsolutePath();
                SharedConfig.saveConfig();

                final File result = apkFile;
                AndroidUtilities.runOnUIThread(() -> callback.onComplete(result));
            } catch (Exception e) {
                boolean cancelled = !isDownloading.get();
                if (apkFile != null && apkFile.exists()) {
                    apkFile.delete();
                }
                if (!cancelled) {
                    FileLog.e(e);
                }
                final String msg = cancelled ? "cancelled" : e.getMessage();
                AndroidUtilities.runOnUIThread(() -> callback.onError(msg));
            } finally {
                isDownloading.set(false);
                currentDownloadConn = null;
                if (conn != null) conn.disconnect();
            }
        });
    }

    /**
     * In-app Tor plugin install. Mirrors {@link #downloadUpdate} but:
     *  - Resolves the asset URL by convention from the current main tag —
     *    main + plugin share MG_BUILD_TAG release-for-release (see AGENTS.md
     *    "Tor plugin"). No GitHub API call needed.
     *  - Reuses {@link #verifyApkSignature}: plugin APK is signed with the
     *    same keystore as main on the GitHub channel (signing-key invariant),
     *    so MG_CERT_SHA256 matches.
     *  - Writes to cache/mg_tor_plugin.apk, not mg_update.apk, so a
     *    concurrent main updater download can't clobber and so a later
     *    main installUpdate(...) doesn't accidentally install the plugin.
     * F-Droid channel callers must gate on {@link #isFdroidBuild()} before
     * calling — F-Droid plugin is signed with a different cert; this method
     * also short-circuits as a safety net.
     */
    public static void downloadPlugin(ProgressCallback callback) {
        if (!isDownloadingPlugin.compareAndSet(false, true)) {
            // Concurrent in-flight download (e.g. Settings-initiated and
            // cold-start prompt both fire): tell the new caller so its
            // progress UI dismisses instead of spinning forever.
            AndroidUtilities.runOnUIThread(() -> callback.onError("Already downloading"));
            return;
        }
        if (isFdroidBuild()) {
            isDownloadingPlugin.set(false);
            AndroidUtilities.runOnUIThread(() -> callback.onError("F-Droid channel"));
            return;
        }
        if (Build.SUPPORTED_ABIS.length == 0) {
            isDownloadingPlugin.set(false);
            AndroidUtilities.runOnUIThread(() -> callback.onError("Unsupported ABI"));
            return;
        }
        final String tag = currentInstallVersion();
        if (tag == null || tag.isEmpty()) {
            isDownloadingPlugin.set(false);
            AndroidUtilities.runOnUIThread(() -> callback.onError("Unknown current version"));
            return;
        }
        final String abi = Build.SUPPORTED_ABIS[0];
        final String url = "https://github.com/Mercurygram/Mercurygram/releases/download/"
                + tag + "/Mercurygram-tor-plugin-" + tag + "-" + abi + ".apk";

        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection conn = null;
            try {
                File cacheDir = new File(ApplicationLoader.getFilesDirFixed(), "cache");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File apkFile = new File(cacheDir, "mg_tor_plugin.apk");

                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    final int rc = responseCode;
                    AndroidUtilities.runOnUIThread(() -> callback.onError("HTTP " + rc));
                    return;
                }

                long total = conn.getContentLength();
                InputStream is = new BufferedInputStream(conn.getInputStream());
                FileOutputStream fos = new FileOutputStream(apkFile);
                byte[] buf = new byte[8192];
                long downloaded = 0;
                int len;
                final long totalFinal = total;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                    downloaded += len;
                    final long dl = downloaded;
                    AndroidUtilities.runOnUIThread(() -> callback.onProgress(dl, totalFinal));
                }
                fos.close();
                is.close();

                if (!verifyApkSignature(apkFile)) {
                    apkFile.delete();
                    AndroidUtilities.runOnUIThread(() -> callback.onError("Signature verification failed"));
                    return;
                }

                final File result = apkFile;
                AndroidUtilities.runOnUIThread(() -> callback.onComplete(result));
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(e.getMessage()));
            } finally {
                isDownloadingPlugin.set(false);
                if (conn != null) conn.disconnect();
            }
        });
    }

    public static void cancelDownload() {
        if (!isDownloading.getAndSet(false)) return;
        final HttpURLConnection conn = currentDownloadConn;
        if (conn != null) {
            // disconnect() off the worker queue (would queue behind the
            // blocking read otherwise). Run on a throwaway thread so the
            // caller (UI) isn't blocked on socket teardown. disconnect()
            // is idempotent → race with worker finally is benign.
            new Thread(conn::disconnect, "MgUpdateCancel").start();
        }
    }

    public static boolean verifyApkSignature(File apkFile) {
        try {
            PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
            PackageInfo pi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi == null || pi.signingInfo == null) return false;
                return matchesMgCert(pi.signingInfo.getApkContentsSigners());
            } else {
                pi = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(),
                        PackageManager.GET_SIGNATURES);
                if (pi == null || pi.signatures == null) return false;
                return matchesMgCert(pi.signatures);
            }
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void installUpdate(Activity activity, File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= 24) {
                Uri uri = FileProvider.getUriForFile(activity,
                        ApplicationLoader.getApplicationId() + ".provider", apkFile);
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
            } else {
                intent.setDataAndType(Uri.fromFile(apkFile),
                        "application/vnd.android.package-archive");
            }
            activity.startActivityForResult(intent, 500);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static File getUpdateApkFile() {
        if (SharedConfig.mgUpdateApkPath != null) {
            File f = new File(SharedConfig.mgUpdateApkPath);
            if (f.exists()) return f;
        }
        return null;
    }

    /**
     * In-app Tor plugin install ceremony shared by all entry points
     * (Settings → Tor toggle and the cold-start mismatch prompt): shows
     * a spinner (320ms delayed so a fast 404 doesn't flash), downloads
     * the per-ABI plugin APK from the GitHub release, verifies its
     * signature, then hands the APK to {@link #installUpdate} for the
     * PackageInstaller intent. On error, toasts MercurygramTorPluginDownloadFailed.
     *
     * <p>{@code activitySource} is invoked lazily inside each callback
     * so a long-running download doesn't pin a destroyed Activity into
     * the lambda's closure; pass {@code fragment::getParentActivity} or
     * a similar accessor that returns null once the host detaches.
     */
    public static void runPluginInstall(java.util.function.Supplier<Activity> activitySource) {
        Activity initial = activitySource != null ? activitySource.get() : null;
        if (initial == null) return;
        final AlertDialog progress = new AlertDialog(initial, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setMessage(LocaleController.getString(R.string.MercurygramTorPluginDownloading));
        progress.setCanCancel(false);
        progress.showDelayed(320);
        downloadPlugin(new ProgressCallback() {
            @Override public void onProgress(long downloaded, long total) {}
            @Override public void onComplete(File apkFile) {
                try { progress.dismiss(); } catch (Throwable ignored) {}
                Activity a = activitySource.get();
                if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                    installUpdate(a, apkFile);
                }
            }
            @Override public void onError(String error) {
                try { progress.dismiss(); } catch (Throwable ignored) {}
                Activity a = activitySource.get();
                if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                    Toast.makeText(a,
                            LocaleController.getString(R.string.MercurygramTorPluginDownloadFailed),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    // Tag of the plugin APK currently installed under `pluginPkg`, or
    // null when no such package is installed. Like main, the plugin's
    // versionName is its build tag verbatim (gradle/mg-version.gradle
    // applies to both modules).
    public static String installedPluginVersion(String pluginPkg) {
        if (pluginPkg == null) return null;
        try {
            PackageInfo pi = ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageInfo(pluginPkg, 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException nf) {
            return null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    // True iff the plugin is installed and strictly behind main's
    // current tag. Main and plugin share MG_BUILD_TAG release-for-release
    // on the GitHub channel — a versionName drift catches a same-
    // MG_VERSION_CODE bump like 12.7.3.2.7 → 12.7.3.2.8 (4th component
    // unchanged, only the 5th K moved, so versionCode is identical
    // between main and a stale plugin). The compare is direction-aware
    // (vector lex compare via versionUpToDate) so a developer running an
    // ahead-of-main plugin isn't nagged to roll back. Returns false when
    // either tag fails toVersionVector — that includes the
    // currentInstallVersion() fallback to BuildVars.BUILD_VERSION_STRING
    // (3-dotted upstream form, e.g. "12.7.3") on PM exception, which
    // would otherwise produce a false-positive against any 4/5-dotted
    // plugin tag. F-Droid channel returns false: the plugin's catalog
    // drives its own update cadence and the signing certs differ so the
    // in-app download path is not authoritative there anyway.
    public static boolean isPluginOutdated(String pluginPkg) {
        if (isFdroidBuild()) return false;
        String installed = installedPluginVersion(pluginPkg);
        if (installed == null) return false;
        String main = currentInstallVersion();
        if (toVersionVector(main) == null) return false;
        if (toVersionVector(installed) == null) return false;
        return !versionUpToDate(installed, main);
    }

    // Returns the tag of the currently installed APK. versionName is set
    // to the GitHub release tag verbatim by gradle/mg-version.gradle, so
    // PackageInfo carries the canonical 5-dotted/4-dotted tag for every
    // build path (CI release, CI beta, F-Droid, sideload).
    public static String currentInstallVersion() {
        // Cached: installing a new APK kills the process, so versionName
        // cannot change under us -- and checkInternal()'s reconciliation puts
        // this on every LaunchActivity resume, a binder IPC each time.
        if (cachedInstallVersion != null) return cachedInstallVersion;
        try {
            PackageInfo pi = ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            return cachedInstallVersion = pi.versionName;
        } catch (Exception e) {
            return BuildVars.BUILD_VERSION_STRING;
        }
    }

    // Returns true iff `current >= tag`. Tag scheme is documented in AGENTS.md;
    // chronology falls out of plain numeric vector compare.
    static boolean versionUpToDate(String current, String tag) {
        long[] c = toVersionVector(current);
        long[] t = toVersionVector(tag);
        if (c == null || t == null) return true;
        return compareVectors(c, t) >= 0;
    }

    private static int compareVectors(long[] a, long[] b) {
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            long x = i < a.length ? a[i] : 0;
            long y = i < b.length ? b[i] : 0;
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }

    // Null on malformed input — caller treats as "refuse update".
    private static long[] toVersionVector(String v) {
        if (v == null || v.isEmpty()) return null;
        String[] p = v.split("\\.", -1);
        if (p.length < 4 || p.length > 5) return null;
        long[] vec = new long[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                vec[i] = Long.parseLong(p[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return vec;
    }

}
