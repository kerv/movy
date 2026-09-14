package sx.movy.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight self-updater.
 *
 * On startup we fetch a small JSON manifest:
 * <pre>
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1",
 *   "apkUrl": "https://kerv.github.io/movy/movy.apk",
 *   "notes": "optional changelog"
 * }
 * </pre>
 * If manifest.versionCode &gt; installed versionCode we download the APK to the
 * app cache and launch the system package installer.
 *
 * IMPORTANT: self-update only works when the new APK is signed with the SAME key
 * as the installed one (Android rejects a re-install with a different signature).
 */
public class Updater {

    private static final String TAG = "MovyUpdater";

    // Manifest URL. Kept as a constant so it is easy to change.
    private static final String MANIFEST_URL = "https://kerv.github.io/movy/version.json";
    // Fallback: if the manifest can't be parsed but this direct APK exists we
    // still won't blindly install (no version info), so it's only informational.
    private static final String DEFAULT_APK_URL = "https://kerv.github.io/movy/movy.apk";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    // Install hand-off state. Written and read on the main thread only.
    private PromptCallback callback;
    private File pendingApk;
    private String pendingDownloadUrl;
    private boolean installLaunched;
    private boolean foreground = true;

    public interface PromptCallback {
        /** Called on the main thread when a newer version is available. */
        void onUpdateAvailable(String versionName, String notes, Runnable startDownload);
        /** Called on the main thread when the APK has finished downloading. */
        void onDownloadComplete();
        /**
         * Called on the main thread when a downloaded APK is ready but the
         * system installer could not be started. Run the callback to retry.
         */
        void onInstallBlocked(Runnable retry);
        /** Called on the main thread on any failure (best-effort, silent). */
        void onError(String message);
    }

    /**
     * Call from the activity's onResume. Installing is an activity start, so it
     * only works while we are actually in the foreground; anything that could
     * not run earlier is picked up here instead of being dropped.
     */
    public void onActivityResumed(final Activity activity) {
        foreground = true;
        if (pendingApk != null && !installLaunched) {
            launchPendingInstall(activity);
            return;
        }
        // The user was sent to Settings to allow installs from this app.
        String url = pendingDownloadUrl;
        if (url != null && callback != null && canInstallPackages(activity)) {
            pendingDownloadUrl = null;
            downloadAndInstall(activity, url, callback);
        }
    }

    /** Call from the activity's onPause. */
    public void onActivityPaused() {
        foreground = false;
    }

    /** Kick off a background check. Safe to call from onCreate. */
    public void checkForUpdate(final Activity activity, final PromptCallback cb) {
        callback = cb;
        io.execute(() -> {
            try {
                JSONObject manifest = fetchManifest();
                if (manifest == null) {
                    return;
                }
                final int remoteCode = manifest.optInt("versionCode", -1);
                final String remoteName = manifest.optString("versionName", "");
                final String apkUrl = manifest.optString("apkUrl", DEFAULT_APK_URL);
                final String notes = manifest.optString("notes", "");
                final int localCode = installedVersionCode(activity);

                Log.i(TAG, "local=" + localCode + " remote=" + remoteCode + " url=" + apkUrl);

                if (remoteCode > localCode && apkUrl != null && apkUrl.startsWith("http")) {
                    main.post(() -> cb.onUpdateAvailable(remoteName, notes,
                            () -> downloadAndInstall(activity, apkUrl, cb)));
                }
            } catch (Exception e) {
                Log.w(TAG, "update check failed", e);
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    private JSONObject fetchManifest() {
        HttpURLConnection c = null;
        try {
            URL url = new URL(MANIFEST_URL);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "manifest HTTP " + code);
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "fetchManifest error", e);
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private boolean canInstallPackages(Activity activity) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls();
    }

    private void downloadAndInstall(final Activity activity, final String apkUrl,
                                    final PromptCallback cb) {
        callback = cb;
        // On Android 8+ the app needs permission to install unknown apps.
        if (!canInstallPackages(activity)) {
            // Resume the download automatically once the grant is given, rather
            // than losing the update because the prompt is gone on return.
            pendingDownloadUrl = apkUrl;
            try {
                Toast.makeText(activity,
                        "Allow Movy TV to install updates. The update continues when you come back.",
                        Toast.LENGTH_LONG).show();
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "cannot open install-permission settings", e);
            }
            return;
        }

        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "cannot create update dir");
                }
                File out = new File(dir, "movy-update.apk");
                if (out.exists() && !out.delete()) {
                    Log.w(TAG, "cannot delete stale apk");
                }

                URL url = new URL(apkUrl);
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setInstanceFollowRedirects(true);
                int code = c.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "apk HTTP " + code);
                    main.post(() -> cb.onError("Download failed (" + code + ")"));
                    return;
                }
                try (InputStream in = c.getInputStream();
                     OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        os.write(buf, 0, n);
                    }
                    os.flush();
                }

                final File ready = out;
                // Starting the installer is an activity start: it must happen on
                // the main thread and while this activity is actually resumed.
                main.post(() -> {
                    cb.onDownloadComplete();
                    pendingApk = ready;
                    installLaunched = false;
                    launchPendingInstall(activity);
                });
            } catch (Exception e) {
                Log.w(TAG, "download error", e);
                main.post(() -> cb.onError(e.getMessage()));
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        });
    }

    private void launchPendingInstall(final Activity activity) {
        final File apk = pendingApk;
        if (apk == null || !apk.exists() || apk.length() == 0) {
            pendingApk = null;
            return;
        }
        if (!foreground || activity.isFinishing()) {
            // Android blocks background activity starts; onActivityResumed retries.
            return;
        }
        if (launchInstaller(activity, apk)) {
            installLaunched = true;
            return;
        }
        final PromptCallback cb = callback;
        if (cb != null) {
            cb.onInstallBlocked(() -> {
                installLaunched = false;
                launchPendingInstall(activity);
            });
        }
    }

    /** @return true when the system installer was actually started. */
    private boolean launchInstaller(Activity activity, File apk) {
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
        } catch (Exception e) {
            Log.w(TAG, "cannot expose the update through FileProvider", e);
            return false;
        }
        return startInstall(activity, new Intent(Intent.ACTION_VIEW), uri)
                || startInstall(activity, new Intent(Intent.ACTION_INSTALL_PACKAGE), uri);
    }

    @SuppressWarnings("deprecation")
    private boolean startInstall(Activity activity, Intent intent, Uri uri) {
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        // Some TV boxes ignore the intent's own grant flag for the installer.
        try {
            List<ResolveInfo> targets = activity.getPackageManager()
                    .queryIntentActivities(intent, 0);
            for (ResolveInfo target : targets) {
                activity.grantUriPermission(target.activityInfo.packageName, uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot pre-grant the installer read access", e);
        }
        try {
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "installer start failed for " + intent.getAction(), e);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private int installedVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pi.getLongVersionCode();
            }
            return pi.versionCode;
        } catch (Exception e) {
            return Integer.MAX_VALUE; // fail closed: never "update" if unknown
        }
    }
}
