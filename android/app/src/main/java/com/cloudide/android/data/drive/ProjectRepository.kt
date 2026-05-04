package com.cloudide.android.data.drive

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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

        // Fetch manifests in parallel for snappy lists.
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

    suspend fun createProject(name: String): ProjectSummary = withContext(Dispatchers.IO) {
        val rootId = rootFolderId()
        val folderId = drive.createFolder(name, rootId)

        val manifest = DriveManifest(
            projectId = UUID.randomUUID().toString(),
            projectName = name,
            version = 1,
            updatedAt = java.time.Instant.now().toString(),
            machineId = "android-${UUID.randomUUID().toString().take(12)}",
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

    suspend fun loadManifest(projectFolderId: String): DriveManifest? =
        drive.fetchManifest(projectFolderId)

    suspend fun loadFile(driveFileId: String): String =
        drive.downloadText(driveFileId)
}
