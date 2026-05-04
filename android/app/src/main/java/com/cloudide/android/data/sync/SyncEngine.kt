package com.cloudide.android.data.sync

import android.os.Build
import com.cloudide.android.data.drive.DriveManifest
import com.cloudide.android.data.drive.DriveService
import com.cloudide.android.data.drive.ManifestFile
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class SyncDiff(
    val toUpload: List<String>,
    val toDelete: List<String>,
    val unchanged: Int,
    val remoteAhead: Boolean,
    val remoteVersion: Int?,
)

data class MergeReport(
    val keptLocal: List<String>,
    val keptRemote: List<String>,
    val conflictsKeptLocal: List<String>,
    val deletedRemotely: List<String>,
    val addedRemotely: List<String>,
)

sealed class SyncResult {
    data class Success(
        val manifest: DriveManifest,
        val diff: SyncDiff?,
        val merge: MergeReport? = null,
    ) : SyncResult()
    data class Error(val message: String, val reason: Reason = Reason.OTHER) : SyncResult()
    enum class Reason { AUTH, CONFLICT, NOT_INITIALIZED, OTHER }
}

class SyncEngine(
    private val drive: DriveService,
    private val cache: LocalProjectCache,
) {
    private val gson = Gson()

    private fun machineId(): String {
        val raw = "android-${Build.MANUFACTURER}-${Build.MODEL}"
        val md = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(12)
    }

    private suspend fun buildLocalEntries(prev: Map<String, LocalFileEntry>): Map<String, LocalFileEntry> {
        val out = mutableMapOf<String, LocalFileEntry>()
        for (rel in cache.listAllRelative()) {
            val file = cache.fileFor(rel)
            if (!file.isFile) continue
            val mtime = file.lastModified()
            val size = file.length()
            val cached = prev[rel]
            if (cached != null && cached.mtime == mtime && cached.size == size) {
                out[rel] = cached
                continue
            }
            val sha = cache.shaOf(rel) ?: continue
            out[rel] = LocalFileEntry(
                sha = sha,
                size = size,
                mtime = mtime,
                driveFileId = cached?.driveFileId,
            )
        }
        return out
    }

    private fun computeDiff(
        local: Map<String, LocalFileEntry>,
        remote: Map<String, ManifestFile>,
        meta: LocalProjectMeta?,
        remoteManifest: DriveManifest?,
    ): SyncDiff {
        val toUpload = local.entries.filter { (k, v) -> remote[k]?.sha != v.sha }.map { it.key }
        val toDelete = remote.keys.filter { it !in local.keys }
        val unchanged = local.size - toUpload.size
        val remoteAhead = remoteManifest != null && meta != null &&
                remoteManifest.version > meta.lastSyncedVersion &&
                remoteManifest.machineId != meta.machineId
        return SyncDiff(
            toUpload = toUpload,
            toDelete = toDelete,
            unchanged = unchanged,
            remoteAhead = remoteAhead,
            remoteVersion = remoteManifest?.version,
        )
    }

    suspend fun diff(): SyncDiff = withContext(Dispatchers.IO) {
        val meta = cache.loadMeta()
        val local = buildLocalEntries(meta?.files.orEmpty())
        val remote = drive.fetchManifest(cache.projectFolderId)
        val remoteFiles = remote?.files ?: meta?.files?.mapValues {
            ManifestFile(it.value.sha, it.value.size, it.value.driveFileId.orEmpty())
        } ?: emptyMap()
        computeDiff(local, remoteFiles, meta, remote)
    }

    /**
     * Initial pull: download everything from remote into the cache. Use this when opening
     * a project for the first time on this device, or after "Force pull" in conflict resolution.
     */
    suspend fun fullPull(projectName: String): SyncResult = withContext(Dispatchers.IO) {
        val remote = drive.fetchManifest(cache.projectFolderId)
            ?: return@withContext SyncResult.Error(
                "Project has no remote manifest yet. Push from desktop or open the project to initialize.",
                SyncResult.Reason.NOT_INITIALIZED,
            )

        val localFiles = mutableMapOf<String, LocalFileEntry>()
        for ((relPath, info) in remote.files) {
            val bytes = runCatching { drive.downloadBytes(info.driveFileId) }.getOrNull() ?: continue
            cache.writeBytes(relPath, bytes)
            val file = cache.fileFor(relPath)
            localFiles[relPath] = LocalFileEntry(
                sha = info.sha,
                size = info.size,
                mtime = file.lastModified(),
                driveFileId = info.driveFileId,
            )
        }
        // Remove any files locally that aren't in the remote manifest.
        for (rel in cache.listAllRelative()) {
            if (rel !in remote.files) cache.deleteFile(rel)
        }

        cache.saveMeta(
            LocalProjectMeta(
                projectId = remote.projectId,
                projectName = projectName,
                driveFolderId = cache.projectFolderId,
                lastSyncedVersion = remote.version,
                lastSyncedAt = System.currentTimeMillis(),
                machineId = machineId(),
                files = localFiles,
            )
        )
        SyncResult.Success(remote, diff = null)
    }

    /** Incremental push of local changes. */
    suspend fun push(projectName: String, force: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        val meta = cache.loadMeta()
            ?: return@withContext SyncResult.Error(
                "Project not initialized locally. Pull first.",
                SyncResult.Reason.NOT_INITIALIZED,
            )

        val local = buildLocalEntries(meta.files)
        val remote = drive.fetchManifest(cache.projectFolderId)

        if (!force && remote != null &&
            remote.version > meta.lastSyncedVersion &&
            remote.machineId != meta.machineId
        ) {
            return@withContext SyncResult.Error(
                "Remote is at version ${remote.version} (changed by another device). Pull first.",
                SyncResult.Reason.CONFLICT,
            )
        }

        // Strict-typed remote files map we'll mutate during push.
        val remoteFiles = mutableMapOf<String, ManifestFile>()
        val seedSource: Map<String, ManifestFile> = remote?.files
            ?: meta.files.mapValues {
                ManifestFile(it.value.sha, it.value.size, it.value.driveFileId.orEmpty())
            }
        for ((k, v) in seedSource) {
            if (v.driveFileId.isNotEmpty()) remoteFiles[k] = v
        }

        val diff = computeDiff(local, remoteFiles, meta, remote)
        if (diff.toUpload.isEmpty() && diff.toDelete.isEmpty()) {
            return@withContext SyncResult.Success(
                remote ?: buildManifestFromMeta(meta, projectName),
                diff,
            )
        }

        val folders = DriveService.FolderCache(drive, cache.projectFolderId)
        val newLocalEntries = local.toMutableMap()

        for (relPath in diff.toUpload) {
            val info = local[relPath] ?: continue
            val bytes = cache.fileFor(relPath).readBytes()
            val dir = relPath.substringBeforeLast('/', "")
            val parentId = folders.ensure(dir)
            val fileName = relPath.substringAfterLast('/')

            val existing = remoteFiles[relPath]
            val driveId = if (existing != null) {
                drive.updateFile(existing.driveFileId, bytes)
            } else {
                val match = drive.findChild(fileName, parentId)
                if (match != null) drive.updateFile(match.id, bytes)
                else drive.uploadFile(fileName, parentId, bytes)
            }
            newLocalEntries[relPath] = info.copy(driveFileId = driveId)
            remoteFiles[relPath] = ManifestFile(info.sha, info.size, driveId)
        }

        for (relPath in diff.toDelete) {
            val id = remoteFiles[relPath]?.driveFileId
            if (id != null) runCatching { drive.deleteFile(id) }
            remoteFiles.remove(relPath)
        }

        val nextVersion = (remote?.version ?: meta.lastSyncedVersion) + 1
        val manifest = DriveManifest(
            projectId = meta.projectId,
            projectName = projectName,
            version = nextVersion,
            updatedAt = Instant.now().toString(),
            machineId = machineId(),
            files = remoteFiles,
        )
        drive.upsertJson(".cloudide-manifest.json", cache.projectFolderId, gson.toJson(manifest))

        cache.saveMeta(
            meta.copy(
                projectName = projectName,
                lastSyncedVersion = nextVersion,
                lastSyncedAt = System.currentTimeMillis(),
                machineId = machineId(),
                files = newLocalEntries,
            )
        )
        SyncResult.Success(manifest, diff)
    }

    suspend fun pull(projectName: String): SyncResult = withContext(Dispatchers.IO) {
        val remote = drive.fetchManifest(cache.projectFolderId)
            ?: return@withContext SyncResult.Error(
                "Remote manifest not found.",
                SyncResult.Reason.NOT_INITIALIZED,
            )
        val meta = cache.loadMeta()
        val localFiles = (meta?.files ?: emptyMap()).toMutableMap()

        // Files in remote that differ from local sha → download.
        for ((rel, info) in remote.files) {
            val current = localFiles[rel]
            if (current?.sha == info.sha) continue
            val bytes = runCatching { drive.downloadBytes(info.driveFileId) }.getOrNull() ?: continue
            cache.writeBytes(rel, bytes)
            val file = cache.fileFor(rel)
            localFiles[rel] = LocalFileEntry(
                sha = info.sha, size = info.size,
                mtime = file.lastModified(), driveFileId = info.driveFileId,
            )
        }
        // Files locally that aren't on remote anymore → delete.
        val toRemoveLocal = localFiles.keys.filter { it !in remote.files }
        for (rel in toRemoveLocal) {
            cache.deleteFile(rel)
            localFiles.remove(rel)
        }

        cache.saveMeta(
            (meta ?: LocalProjectMeta(
                projectId = remote.projectId, projectName = projectName,
                driveFolderId = cache.projectFolderId,
                lastSyncedVersion = 0, lastSyncedAt = 0L, machineId = machineId(),
                files = emptyMap(),
            )).copy(
                projectName = projectName,
                lastSyncedVersion = remote.version,
                lastSyncedAt = System.currentTimeMillis(),
                files = localFiles,
            )
        )
        SyncResult.Success(remote, diff = null)
    }

    /**
     * Three-way merge: baseline = meta.files (the snapshot at last sync), local = current cache,
     * remote = current Drive manifest. Applies the merge to the cache, then runs a normal push so
     * the resolved state lands on Drive. Returns a [MergeReport] describing what happened so the
     * UI can show "kept your local edits to X, took remote Y" — that's strictly more informative
     * than the old 'pull-overwrites-then-push' behavior.
     */
    suspend fun mergeAndPush(projectName: String): SyncResult = withContext(Dispatchers.IO) {
        val meta = cache.loadMeta()
            ?: return@withContext SyncResult.Error(
                "Project not initialized locally.", SyncResult.Reason.NOT_INITIALIZED
            )
        val baseline = meta.files
        val current = buildLocalEntries(baseline)
        val remote = drive.fetchManifest(cache.projectFolderId)
            ?: return@withContext SyncResult.Error(
                "Remote manifest not found.", SyncResult.Reason.NOT_INITIALIZED
            )

        val keptLocal = mutableListOf<String>()
        val keptRemote = mutableListOf<String>()
        val conflictsKeptLocal = mutableListOf<String>()
        val deletedRemotely = mutableListOf<String>()
        val addedRemotely = mutableListOf<String>()

        // 1. Walk every relative path that appears in any of the three sets.
        val allPaths = (baseline.keys + current.keys + remote.files.keys).toSet()

        for (rel in allPaths) {
            val base = baseline[rel]
            val local = current[rel]
            val remoteEntry = remote.files[rel]

            when {
                local != null && remoteEntry != null -> {
                    // File exists in both. Compare to baseline.
                    when {
                        local.sha == remoteEntry.sha -> {
                            // Already converged. Refresh driveFileId in case it differs.
                        }
                        base != null && local.sha == base.sha -> {
                            // Local untouched since baseline; remote changed → take remote.
                            val bytes = drive.downloadBytes(remoteEntry.driveFileId)
                            cache.writeBytes(rel, bytes)
                            keptRemote.add(rel)
                        }
                        base != null && remoteEntry.sha == base.sha -> {
                            // Remote unchanged since baseline; local changed → keep local.
                            keptLocal.add(rel)
                        }
                        else -> {
                            // Both diverged from baseline (or no baseline). Default: keep local
                            // — the user's most recent edits — and surface the conflict.
                            conflictsKeptLocal.add(rel)
                        }
                    }
                }
                local != null && remoteEntry == null -> {
                    // Not on remote. Either added locally OR remote deleted it.
                    if (base != null && base.sha == local.sha) {
                        // Was synced, now gone remotely, locally untouched → delete locally.
                        cache.deleteFile(rel)
                        deletedRemotely.add(rel)
                    } else {
                        // Newly added locally OR locally edited & remote deleted → keep local.
                        keptLocal.add(rel)
                    }
                }
                local == null && remoteEntry != null -> {
                    // Missing locally. Either added remotely OR locally deleted.
                    if (base != null && base.sha == remoteEntry.sha) {
                        // We deleted it locally; remote unchanged → keep deleted (push will remove).
                    } else {
                        // Added remotely OR locally deleted while remote modified → take remote.
                        val bytes = drive.downloadBytes(remoteEntry.driveFileId)
                        cache.writeBytes(rel, bytes)
                        if (base == null) addedRemotely.add(rel) else conflictsKeptLocal.add(rel)
                    }
                }
            }
        }

        // 2. Update meta to reflect the new baseline (we've incorporated remote changes), then push.
        val refreshedFiles = mutableMapOf<String, LocalFileEntry>()
        for (rel in cache.listAllRelative()) {
            val file = cache.fileFor(rel)
            if (!file.isFile) continue
            val sha = cache.shaOf(rel) ?: continue
            refreshedFiles[rel] = LocalFileEntry(
                sha = sha,
                size = file.length(),
                mtime = file.lastModified(),
                driveFileId = remote.files[rel]?.driveFileId ?: meta.files[rel]?.driveFileId,
            )
        }
        cache.saveMeta(
            meta.copy(
                lastSyncedVersion = remote.version,
                lastSyncedAt = System.currentTimeMillis(),
                files = refreshedFiles,
            )
        )

        val pushed = push(projectName, force = false)
        when (pushed) {
            is SyncResult.Success -> SyncResult.Success(
                manifest = pushed.manifest,
                diff = pushed.diff,
                merge = MergeReport(
                    keptLocal = keptLocal,
                    keptRemote = keptRemote,
                    conflictsKeptLocal = conflictsKeptLocal,
                    deletedRemotely = deletedRemotely,
                    addedRemotely = addedRemotely,
                ),
            )
            is SyncResult.Error -> pushed
        }
    }

    private fun buildManifestFromMeta(meta: LocalProjectMeta, projectName: String): DriveManifest {
        val files = meta.files.mapNotNull { (k, v) ->
            val id = v.driveFileId ?: return@mapNotNull null
            k to ManifestFile(v.sha, v.size, id)
        }.toMap()
        return DriveManifest(
            projectId = meta.projectId,
            projectName = projectName,
            version = meta.lastSyncedVersion,
            updatedAt = Instant.ofEpochMilli(meta.lastSyncedAt).toString(),
            machineId = meta.machineId,
            files = files,
        )
    }
}
