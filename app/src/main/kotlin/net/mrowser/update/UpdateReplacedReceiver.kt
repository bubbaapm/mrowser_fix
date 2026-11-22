package net.mrowser.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * MY_PACKAGE_REPLACED is exempt from Android 8 implicit-broadcast restrictions.
 * It arrives after Android has actually installed the replacement APK.
 */
class UpdateReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val token = goAsync()
        val appContext = context.applicationContext
        Thread({
            try {
                UpdateApkCleanup.afterSuccessfulInstall(appContext)
            } catch (e: Exception) {
                Log.w("UpdateApkCleanup", "Post-update APK cleanup failed", e)
            } finally {
                token.finish()
            }
        }, "mrowser-apk-cleanup").start()
    }
}
