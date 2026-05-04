package com.cloudide.android.data.sync

import android.content.Context
import com.cloudide.android.data.drive.DriveManifest
import com.cloudide.android.data.drive.ManifestFile
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Per-project cache layer. Files live under <filesDir>/projects/<projectFolderId>/.
 * The cache is the user-visible "local" working copy on Android — there is no analog of
 * the user's filesystem like on desktop, so this folder *is* the working tree.
 */
class LocalProjectCache(
    private val context: Context,
    val projectFolderId: String,
) {
    val rootDir: File = File(context.filesDir, "projects/$projectFolderId").apply { mkdirs() }
    private val metaFile: File = File(rootDir, ".cloudide/project.json").apply { parentFile?.mkdirs() }
    private val gson = Gson()

    fun fileFor(relativePath: String): File =
        File(rootDir, relativePath.replace('/', File.separatorChar))

    fun listAllRelative(): List<String> {
        val out = mutableListOf<String>()
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.name == ".cloudide") continue
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) walk(child, rel)
                else out.add(rel)
            }
        }
        walk(rootDir, "")
        return out
    }

    suspend fun readText(relativePath: String): String? = withContext(Dispatchers.IO) {
        runCatching { fileFor(relativePath).readText(Charsets.UTF_8) }.getOrNull()
    }

    suspend fun writeText(relativePath: String, content: String) = withContext(Dispatchers.IO) {
        val target = fileFor(relativePath)
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
    }

    suspend fun writeBytes(relativePath: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = fileFor(relativePath)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    suspend fun deleteFile(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val target = fileFor(relativePath)
        val ok = target.delete()
        // Prune now-empty parent directories up to root.
        var dir = target.parentFile
        while (dir != null && dir != rootDir && dir.isDirectory && dir.listFiles().isNullOrEmpty()) {
            dir.delete()
            dir = dir.parentFile
        }
        ok
    }

    suspend fun renameFile(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        val source = fileFor(oldPath)
        val target = fileFor(newPath)
        target.parentFile?.mkdirs()
        val ok = source.renameTo(target)
        if (ok) {
            var dir = source.parentFile
            while (dir != null && dir != rootDir && dir.isDirectory && dir.listFiles().isNullOrEmpty()) {
                dir.delete()
                dir = dir.parentFile
            }
        }
        ok
    }

    /** True if `relativePath` resolves to a directory in the cache. */
    fun isDirectory(relativePath: String): Boolean {
        if (relativePath.isEmpty()) return true
        val f = fileFor(relativePath)
        return f.isDirectory
    }

    /**
     * Move/rename a directory. Walks the children, moving each file individually to preserve
     * any in-flight handles, then prunes empty source directories. Returns the list of file
     * pairs (oldRel, newRel) that were moved — useful for surfacing what changed.
     */
    suspend fun renameDir(oldDirPath: String, newDirPath: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val moves = mutableListOf<Pair<String, String>>()
        val sourceRoot = fileFor(oldDirPath)
        if (!sourceRoot.isDirectory) return@withContext moves

        val targetRoot = fileFor(newDirPath)
        // Refuse to overwrite an existing target.
        if (targetRoot.exists()) return@withContext moves
        targetRoot.parentFile?.mkdirs()

        // Try the cheap rename first; if the platform allows it (same filesystem) we're done.
        if (sourceRoot.renameTo(targetRoot)) {
            for (rel in listAllRelative()) {
                if (rel.startsWith("$newDirPath/")) {
                    val oldRel = oldDirPath + rel.removePrefix(newDirPath)
                    moves.add(oldRel to rel)
                }
            }
            // Prune any now-empty parents of the source.
            var dir = sourceRoot.parentFile
            while (dir != null && dir != rootDir && dir.isDirectory && dir.listFiles().isNullOrEmpty()) {
                dir.delete()
                dir = dir.parentFile
            }
            return@withContext moves
        }

        // Fall back to per-file copy.
        fun walk(dir: File, relPrefixOld: String, relPrefixNew: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val nameOld = if (relPrefixOld.isEmpty()) child.name else "$relPrefixOld/${child.name}"
                val nameNew = if (relPrefixNew.isEmpty()) child.name else "$relPrefixNew/${child.name}"
                if (child.isDirectory) {
                    fileFor(nameNew).mkdirs()
                    walk(child, nameOld, nameNew)
                } else if (child.isFile) {
                    val target = fileFor(nameNew)
                    target.parentFile?.mkdirs()
                    child.copyTo(target, overwrite = true)
                    child.delete()
                    moves.add(nameOld to nameNew)
                }
            }
        }
        walk(sourceRoot, oldDirPath, newDirPath)

        // Prune the now-empty source tree.
        sourceRoot.deleteRecursively()
        var dir = sourceRoot.parentFile
        while (dir != null && dir != rootDir && dir.isDirectory && dir.listFiles().isNullOrEmpty()) {
            dir.delete()
            dir = dir.parentFile
        }
        moves
    }

    /** True if any file's relative path starts with `relativeDir + '/'`. */
    fun directoryContains(relativeDir: String): Boolean {
        if (relativeDir.isEmpty()) return listAllRelative().isNotEmpty()
        val prefix = "$relativeDir/"
        return listAllRelative().any { it.startsWith(prefix) }
    }

    suspend fun shaOf(relativePath: String): String? = withContext(Dispatchers.IO) {
        val file = fileFor(relativePath)
        if (!file.isFile) return@withContext null
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    fun loadMeta(): LocalProjectMeta? = runCatching {
        if (!metaFile.exists()) null
        else gson.fromJson(metaFile.readText(), LocalProjectMeta::class.java)
    }.getOrNull()

    fun saveMeta(meta: LocalProjectMeta) {
        metaFile.parentFile?.mkdirs()
        metaFile.writeText(gson.toJson(meta), Charsets.UTF_8)
    }

    fun clearMeta() {
        metaFile.delete()
    }
}

data class LocalProjectMeta(
    val projectId: String,
    val projectName: String,
    val driveFolderId: String,
    val lastSyncedVersion: Int,
    val lastSyncedAt: Long,
    val machineId: String,
    val files: Map<String, LocalFileEntry>,
)

data class LocalFileEntry(
    val sha: String,
    val size: Long,
    val mtime: Long,
    val driveFileId: String?,
)

internal fun manifestFiles(manifest: DriveManifest): Map<String, ManifestFile> = manifest.files
