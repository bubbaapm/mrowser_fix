package net.mrowser.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import net.mrowser.download.DownloadStatus
import net.mrowser.download.Downloader

/**
 * Deliberately narrow cleanup: only the *completed* GitHub experimental APK for
 * the version that Android actually reports as installed can be removed.
 *
 * Using the DownloadManager's original request URL avoids deleting unrelated APK
 * downloads, canceled installs, and APKs from other versions. This also works
 * during the first upgrade to a version containing this feature, since the
 * DownloadManager entry is retained across in-place package replacements.
 */
internal object UpdateApkCleanupPolicy {
    private const val BASE_URL =
        "https://github.com/bubbaapm/mrowser_fix/releases/download/v"
    private const val ASSET_NAME = "mrowser-experimental.apk"

    fun shouldRemove(
        downloadedUrl: String,
        status: DownloadStatus,
        installedVersion: String
    ): Boolean {
        if (status != DownloadStatus.SUCCESSFUL) return false
        if (VersionCompare.compare(installedVersion, installedVersion) == null) return false
        return downloadedUrl == BASE_URL + installedVersion + "/" + ASSET_NAME
    }
}

object UpdateApkCleanup {
    private const val TAG = "UpdateApkCleanup"

    /** Safe to call both on package replacement and when the app is next launched. */
    fun afterSuccessfulInstall(context: Context) {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Cannot determine installed version", e)
            return
        } ?: return

        // A failed DownloadManager query is NOT evidence that the APK is absent.
        // Keep the file, and let the next launch/broadcast retry the cleanup.
        val downloads = Downloader.query(context) ?: return
        for (download in downloads) {
            if (!UpdateApkCleanupPolicy.shouldRemove(
                    download.url, download.status, version
                )
            ) continue

            if (Downloader.remove(context, download.id)) {
                Log.i(TAG, "Removed installed update APK for version " + version)
            } else {
                Log.w(TAG, "Could not remove installed update APK for version " + version)
            }
        }
    }
}
