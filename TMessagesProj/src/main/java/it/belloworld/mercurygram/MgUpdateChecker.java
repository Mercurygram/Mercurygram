package it.belloworld.mercurygram;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

public class MgUpdateChecker {

    private static final String GITHUB_API_URL = "https://api.github.com/repos/Mercurygram/Mercurygram/releases/latest";
    private static final String MG_CERT_SHA256 = "1E73DE100E2646BE671AFAD2CB4BB471538E062A745AE5ADBE6C7D1666FD1EE9";
    private static final long CHECK_INTERVAL = 3600 * 1000; // 1 hour

    private static Boolean isFdroidBuildCached = null;
    private static final AtomicBoolean isDownloading = new AtomicBoolean(false);

    public interface ProgressCallback {
        void onProgress(long downloaded, long total);
        void onComplete(File apkFile);
        void onError(String error);
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
        if (isFdroidBuild()) return;

        if (!force && Math.abs(System.currentTimeMillis() - SharedConfig.mgLastUpdateCheckTime) < CHECK_INTERVAL) {
            return;
        }

        Utilities.globalQueue.postRunnable(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(GITHUB_API_URL).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    return;
                }

                InputStream is = conn.getInputStream();
                byte[] buf = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int len;
                while ((len = is.read(buf)) != -1) {
                    sb.append(new String(buf, 0, len));
                }
                is.close();
                conn.disconnect();

                JSONObject release = new JSONObject(sb.toString());
                String tagName = release.getString("tag_name");

                String currentVersion = getCurrentVersionName();
                if (SharedConfig.versionBiggerOrEqual(currentVersion, tagName)) {
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

                String abiApkName = "Mercurygram-" + tagName + "-" + targetAbi + ".apk";
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

                SharedConfig.mgLastUpdateCheckTime = System.currentTimeMillis();
                SharedConfig.setMgPendingUpdate(info);

                final MgUpdateInfo finalInfo = info;
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
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
            try {
                File cacheDir = new File(ApplicationLoader.getFilesDirFixed(), "cache");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File apkFile = new File(cacheDir, "mg_update.apk");

                conn = (HttpURLConnection) new URL(info.downloadUrl).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);

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
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(e.getMessage()));
            } finally {
                isDownloading.set(false);
                if (conn != null) conn.disconnect();
            }
        });
    }

    public static void cancelDownload() {
        // The download thread checks isDownloading; setting to false won't interrupt
        // but the next checkForUpdates won't start a new one
        isDownloading.set(false);
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

    private static String getCurrentVersionName() {
        try {
            PackageInfo pi = ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return BuildVars.BUILD_VERSION_STRING;
        }
    }
}
