package com.cloudide.android.data.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.cloudide.android.data.sync.LocalProjectCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a folder picked through Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE)
 * and copies its file tree into [LocalProjectCache].
 *
 * Skips common build/cache directories so importing a Node project doesn't accidentally
 * copy 200 MB of node_modules.
 */
class DeviceFolderImporter(
    private val context: Context,
    private val cache: LocalProjectCache,
) {
    data class ImportProgress(
        val current: Int,
        val total: Int,
        val relativePath: String,
    )

    data class ImportResult(
        val imported: Int,
        val skipped: List<SkipReason>,
    )

    data class SkipReason(val path: String, val reason: String)

    suspend fun import(
        treeUri: Uri,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ImportResult(imported = 0, skipped = emptyList())

        // First pass: count files for progress and collect (file, relativePath).
        val files = mutableListOf<Pair<DocumentFile, String>>()
        collectFiles(root, "", files)

        val total = files.size
        val skipped = mutableListOf<SkipReason>()
        var imported = 0
        for ((doc, rel) in files) {
            onProgress(ImportProgress(current = imported, total = total, relativePath = rel))
            val bytes = readBytes(doc)
            if (bytes == null) {
                skipped.add(SkipReason(rel, "Could not read"))
                continue
            }
            if (bytes.size > MAX_FILE_SIZE) {
                skipped.add(SkipReason(rel, "Larger than ${MAX_FILE_SIZE / 1024 / 1024} MB"))
                continue
            }
            cache.writeBytes(rel, bytes)
            imported += 1
        }
        onProgress(ImportProgress(current = imported, total = total, relativePath = ""))
        ImportResult(imported = imported, skipped = skipped)
    }

    private fun collectFiles(dir: DocumentFile, prefix: String, out: MutableList<Pair<DocumentFile, String>>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                if (name in IGNORED_DIRS) continue
                val nextPrefix = if (prefix.isEmpty()) name else "$prefix/$name"
                collectFiles(child, nextPrefix, out)
            } else if (child.isFile) {
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                out.add(child to rel)
            }
        }
    }

    private fun readBytes(file: DocumentFile): ByteArray? {
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_FILE_SIZE = 100 * 1024 * 1024  // 100 MB

        private val IGNORED_DIRS = setOf(
            "node_modules", ".git", "dist", "build", ".next", ".turbo",
            ".cache", ".parcel-cache", ".venv", "__pycache__", ".idea",
            ".gradle", ".kotlin", "out", "target", ".cloudide",
        )
    }
}
