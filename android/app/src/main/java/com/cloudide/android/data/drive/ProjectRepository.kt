package com.cloudide.android.data.drive

import android.content.Context
import android.net.Uri
import android.os.Build
import com.cloudide.android.data.importer.DeviceFolderImporter
import com.cloudide.android.data.sync.LocalProjectCache
import com.cloudide.android.data.sync.SyncEngine
import com.cloudide.android.data.sync.SyncResult
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

class ProjectRepository(private val drive: DriveService) {
    private val gson = Gson()

    /** Returns the Drive folder ID of CloudIDE/, creating it if missing. */
    private suspend fun rootFolderId(): String =
        drive.ensureFolder(CLOUDIDE_ROOT_FOLDER_NAME, parentId = "root")

    suspend fun listProjects(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        val rootId = rootFolderId()
        val children = drive.listChildren(rootId)
            .filter { it.mimeType == "application/vnd.google-apps.folder" }
            .sortedByDescending { it.modifiedTime ?: "" }

        coroutineScope {
            children.map { folder ->
                async {
                    val manifest = runCatching { drive.fetchManifest(folder.id) }.getOrNull()
                    ProjectSummary(
                        driveFolderId = folder.id,
                        name = folder.name,
                        modifiedTime = folder.modifiedTime,
                        manifest = manifest,
                    )
                }
            }.map { it.await() }
        }
    }

    private fun deviceMachineId(): String {
        val raw = "android-${Build.MANUFACTURER}-${Build.MODEL}"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
    }

    suspend fun createProject(name: String): ProjectSummary = withContext(Dispatchers.IO) {
        val rootId = rootFolderId()
        val folderId = drive.createFolder(name, rootId)

        val manifest = DriveManifest(
            projectId = UUID.randomUUID().toString(),
            projectName = name,
            version = 1,
            updatedAt = java.time.Instant.now().toString(),
            machineId = deviceMachineId(),
            files = emptyMap(),
        )
        drive.upsertJson(".cloudide-manifest.json", folderId, gson.toJson(manifest))

        ProjectSummary(
            driveFolderId = folderId,
            name = name,
            modifiedTime = manifest.updatedAt,
            manifest = manifest,
        )
    }

    /**
     * Create a project from an existing folder on the device. Walks the picked SAF tree URI,
     * copies files into the local cache, then pushes everything to Drive.
     *
     * Reports progress through [onProgress] so the UI can show "Importing 17/120: src/foo.ts".
     * Phases (so callers can label appropriately): "scan", "import", "upload".
     */
    suspend fun createProjectFromDevice(
        appContext: Context,
        name: String,
        treeUri: Uri,
        onProgress: (phase: String, current: Int, total: Int, message: String) -> Unit = { _, _, _, _ -> },
    ): ProjectSummary = withContext(Dispatchers.IO) {
        // 1) Create the Drive folder + an initial empty manifest so we have a folder ID and meta.
        onProgress("scan", 0, 0, "Creating Drive folder…")
        val initial = createProject(name)
        val cache = LocalProjectCache(appContext, initial.driveFolderId)

        // Seed local meta so SyncEngine.push() will work afterward.
        val seedMeta = com.cloudide.android.data.sync.LocalProjectMeta(
            projectId = initial.manifest!!.projectId,
            projectName = name,
            driveFolderId = initial.driveFolderId,
            lastSyncedVersion = 1,
            lastSyncedAt = System.currentTimeMillis(),
            machineId = deviceMachineId(),
            files = emptyMap(),
        )
        cache.saveMeta(seedMeta)

        // 2) Walk the device folder, copying each file into the cache.
        val importer = DeviceFolderImporter(appContext, cache)
        val result = importer.import(treeUri) { progress ->
            onProgress(
                "import",
                progress.current,
                progress.total,
                if (progress.relativePath.isNotEmpty()) progress.relativePath else "Imported all files",
            )
        }

        // 3) Push to Drive via the existing sync engine. This bumps version to 2 with all files.
        onProgress("upload", 0, result.imported, "Uploading to Drive…")
        val sync = SyncEngine(drive, cache)
        val pushed = sync.push(name)
        val finalManifest = if (pushed is SyncResult.Success) pushed.manifest else initial.manifest

        ProjectSummary(
            driveFolderId = initial.driveFolderId,
            name = name,
            modifiedTime = finalManifest?.updatedAt ?: initial.modifiedTime,
            manifest = finalManifest,
        )
    }

    suspend fun loadManifest(projectFolderId: String): DriveManifest? =
        drive.fetchManifest(projectFolderId)

    suspend fun loadFile(driveFileId: String): String =
        drive.downloadText(driveFileId)
}
