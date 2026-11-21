package net.mrowser.update

import net.mrowser.download.DownloadStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateApkCleanupPolicyTest {
    private val installed = "1.5.12"
    private val exact =
        "https://github.com/bubbaapm/mrowser_fix/releases/download/v1.5.12/mrowser-experimental.apk"

    @Test fun completedInstalledVersionIsRemoved() {
        assertTrue(UpdateApkCleanupPolicy.shouldRemove(
            exact, DownloadStatus.SUCCESSFUL, installed))
    }

    @Test fun pendingFailedOrCanceledDownloadsAreNotRemoved() {
        for (status in DownloadStatus.values()) {
            if (status == DownloadStatus.SUCCESSFUL) continue
            assertFalse(UpdateApkCleanupPolicy.shouldRemove(exact, status, installed))
        }
    }

    @Test fun olderAndNewerVersionsAreNotRemoved() {
        assertFalse(UpdateApkCleanupPolicy.shouldRemove(
            "https://github.com/bubbaapm/mrowser_fix/releases/download/v1.5.11/mrowser-experimental.apk",
            DownloadStatus.SUCCESSFUL, installed))
        assertFalse(UpdateApkCleanupPolicy.shouldRemove(
            "https://github.com/bubbaapm/mrowser_fix/releases/download/v1.5.13/mrowser-experimental.apk",
            DownloadStatus.SUCCESSFUL, installed))
    }

    @Test fun unrelatedApksAndHostsAreNotRemoved() {
        assertFalse(UpdateApkCleanupPolicy.shouldRemove(
            "https://example.com/releases/download/v1.5.12/mrowser-experimental.apk",
            DownloadStatus.SUCCESSFUL, installed))
        assertFalse(UpdateApkCleanupPolicy.shouldRemove(
            "https://github.com/bubbaapm/mrowser_fix/releases/download/v1.5.12/other.apk",
            DownloadStatus.SUCCESSFUL, installed))
        assertFalse(UpdateApkCleanupPolicy.shouldRemove(
            "https://github.com/m-salehi-v/mrowser/releases/download/v1.5.12/app-release.apk",
            DownloadStatus.SUCCESSFUL, installed))
    }

    @Test fun invalidInstalledVersionsAreIgnored() {
        assertFalse(UpdateApkCleanupPolicy.shouldRemove(
            exact, DownloadStatus.SUCCESSFUL, "unknown"))
    }
}
