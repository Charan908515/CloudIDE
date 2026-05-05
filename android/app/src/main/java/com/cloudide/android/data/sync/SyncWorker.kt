package com.cloudide.android.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cloudide.android.CloudIdeApp
import com.cloudide.android.MainActivity
import java.io.File

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? CloudIdeApp ?: return Result.failure()
        val driveService = app.driveService
        
        // Skip if not signed in
        val account = app.authManager.user.value
        if (account == null) {
            Log.d("SyncWorker", "Not signed in, skipping sync check.")
            return Result.success()
        }

        val projectsDir = File(applicationContext.filesDir, "projects")
        val projectDirs = projectsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()

        var notificationsSent = 0

        for (dir in projectDirs) {
            val projectFolderId = dir.name
            val cache = LocalProjectCache(applicationContext, projectFolderId)
            val meta = cache.loadMeta() ?: continue

            try {
                val manifest = driveService.fetchManifest(projectFolderId)
                if (manifest != null && manifest.version > meta.lastSyncedVersion) {
                    showNotification(
                        projectId = projectFolderId,
                        projectName = meta.projectName,
                        version = manifest.version
                    )
                    notificationsSent++
                }
            } catch (e: Exception) {
                Log.e("SyncWorker", "Failed to check project ${meta.projectName}", e)
            }
        }

        Log.d("SyncWorker", "Checked ${projectDirs.size} projects, sent $notificationsSent notifications.")
        return Result.success()
    }

    private fun showNotification(projectId: String, projectName: String, version: Int) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = "cloudide_sync_updates"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "CloudIDE Project Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when a remote project is updated."
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // We could pass projectId here if we want to deep link to the project screen later
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            applicationContext,
            projectId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Project Update Available")
            .setContentText("'$projectName' has a new version (v$version) on Drive.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(projectId.hashCode(), notification)
    }
}
