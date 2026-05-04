package com.cloudide.android.data.drive

import com.cloudide.android.data.auth.AuthManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
private const val FOLDER_MIME = "application/vnd.google-apps.folder"
private const val MANIFEST_FILE_NAME = ".cloudide-manifest.json"
const val CLOUDIDE_ROOT_FOLDER_NAME = "CloudIDE"

class DriveService(
    private val client: OkHttpClient,
    private val authManager: AuthManager,
) {
    private val gson = Gson()

    private suspend fun token(): String =
        authManager.accessToken() ?: throw IllegalStateException("Not signed in")

    private fun authedGet(url: String, token: String) =
        Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()

    private suspend inline fun <reified T> getJson(url: String): T = withContext(Dispatchers.IO) {
        val token = token()
        client.newCall(authedGet(url, token)).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}: ${res.message}")
            gson.fromJson(res.body?.string().orEmpty(), T::class.java)
        }
    }

    suspend fun listChildren(parentId: String): List<DriveFile> {
        val q = "'$parentId' in parents and trashed=false"
        val url = "$DRIVE_BASE/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id,name,mimeType,modifiedTime,size)")
            .addQueryParameter("pageSize", "200")
            .build().toString()
        return getJson<DriveFileList>(url).files
    }

    suspend fun findChild(name: String, parentId: String): DriveFile? {
        val escaped = name.replace("'", "\\'")
        val q = "name='$escaped' and trashed=false and '$parentId' in parents"
        val url = "$DRIVE_BASE/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id,name,mimeType,modifiedTime,size)")
            .addQueryParameter("pageSize", "1")
            .build().toString()
        return getJson<DriveFileList>(url).files.firstOrNull()
    }

    suspend fun ensureFolder(name: String, parentId: String = "root"): String {
        val existing = findChild(name, parentId)
        if (existing != null && existing.mimeType == FOLDER_MIME) return existing.id
        return createFolder(name, parentId)
    }

    suspend fun createFolder(name: String, parentId: String = "root"): String = withContext(Dispatchers.IO) {
        val token = token()
        val metadata = gson.toJson(
            mapOf("name" to name, "mimeType" to FOLDER_MIME, "parents" to listOf(parentId))
        )
        val req = Request.Builder()
            .url("$DRIVE_BASE/files?fields=id")
            .addHeader("Authorization", "Bearer $token")
            .post(metadata.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}: ${res.message}")
            (gson.fromJson(res.body?.string().orEmpty(), Map::class.java)["id"] as? String)
                ?: throw IOException("Folder creation returned no id")
        }
    }

    /** Upload a brand-new file. Returns its Drive file id. */
    suspend fun uploadFile(
        name: String,
        parentId: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream",
    ): String = withContext(Dispatchers.IO) {
        val token = token()
        val metadata = gson.toJson(mapOf("name" to name, "parents" to listOf(parentId)))
        val multipart = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val req = Request.Builder()
            .url("$UPLOAD_BASE/files?uploadType=multipart&fields=id")
            .addHeader("Authorization", "Bearer $token")
            .post(multipart).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}: ${res.message}")
            (gson.fromJson(res.body?.string().orEmpty(), Map::class.java)["id"] as? String)
                ?: throw IOException("Upload returned no id")
        }
    }

    /** Replace contents of an existing Drive file in place. Returns the same id. */
    suspend fun updateFile(
        fileId: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream",
    ): String = withContext(Dispatchers.IO) {
        val token = token()
        val req = Request.Builder()
            .url("$UPLOAD_BASE/files/$fileId?uploadType=media&fields=id")
            .addHeader("Authorization", "Bearer $token")
            .patch(bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}: ${res.message}")
            (gson.fromJson(res.body?.string().orEmpty(), Map::class.java)["id"] as? String)
                ?: throw IOException("Update returned no id")
        }
    }

    /** Convenience: write a JSON file (manifest etc.). Creates or replaces. */
    suspend fun upsertJson(name: String, parentId: String, json: String): String {
        val existing = findChild(name, parentId)
        return if (existing != null) {
            updateFile(existing.id, json.toByteArray(Charsets.UTF_8), "application/json")
        } else {
            uploadFile(name, parentId, json.toByteArray(Charsets.UTF_8), "application/json")
        }
    }

    suspend fun deleteFile(fileId: String) = withContext(Dispatchers.IO) {
        val token = token()
        val req = Request.Builder().url("$DRIVE_BASE/files/$fileId")
            .addHeader("Authorization", "Bearer $token")
            .delete().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful && res.code != 404) {
                throw IOException("HTTP ${res.code}: ${res.message}")
            }
        }
    }

    /** Download text contents of a file. */
    suspend fun downloadText(fileId: String): String = withContext(Dispatchers.IO) {
        val token = token()
        client.newCall(authedGet("$DRIVE_BASE/files/$fileId?alt=media", token)).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}: ${res.message}")
            res.body?.string().orEmpty()
        }
    }

    /** Download raw bytes of a file. */
    suspend fun downloadBytes(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        val token = token()
        client.newCall(authedGet("$DRIVE_BASE/files/$fileId?alt=media", token)).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}: ${res.message}")
            res.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun fetchManifest(projectFolderId: String): DriveManifest? {
        val file = findChild(MANIFEST_FILE_NAME, projectFolderId) ?: return null
        return runCatching { gson.fromJson(downloadText(file.id), DriveManifest::class.java) }.getOrNull()
    }

    /** Resolve & cache nested folder path "src/utils" under a project folder. */
    class FolderCache(private val drive: DriveService, projectFolderId: String) {
        private val cache = mutableMapOf<String, String>("" to projectFolderId)
        suspend fun ensure(relDir: String): String {
            cache[relDir]?.let { return it }
            val parts = relDir.split('/').filter { it.isNotEmpty() }
            var parent = cache[""]!!
            var walked = ""
            for (part in parts) {
                walked = if (walked.isEmpty()) part else "$walked/$part"
                val cached = cache[walked]
                if (cached != null) {
                    parent = cached
                } else {
                    val id = drive.ensureFolder(part, parent)
                    cache[walked] = id
                    parent = id
                }
            }
            return parent
        }
    }
}

const val MANIFEST_FILE = ".cloudide-manifest.json"
