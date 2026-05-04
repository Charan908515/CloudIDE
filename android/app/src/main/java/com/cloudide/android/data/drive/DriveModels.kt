package com.cloudide.android.data.drive

import com.google.gson.annotations.SerializedName

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String? = null,
    val modifiedTime: String? = null,
    val size: String? = null,
)

data class DriveFileList(val files: List<DriveFile> = emptyList())

data class DriveManifest(
    val projectId: String,
    val projectName: String,
    val version: Int,
    val updatedAt: String? = null,
    val machineId: String? = null,
    val files: Map<String, ManifestFile> = emptyMap(),
)

data class ManifestFile(
    val sha: String,
    val size: Long,
    @SerializedName("driveFileId") val driveFileId: String,
)

data class ProjectSummary(
    val driveFolderId: String,
    val name: String,
    val modifiedTime: String?,
    val manifest: DriveManifest?,
)
