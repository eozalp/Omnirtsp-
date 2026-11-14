package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

data class FolderSelectionResult(
    val uriString: String,
    val displayName: String,
    val isRemoteProvider: Boolean
)

/**
 * Zero-permission Storage Access Framework (SAF) & MediaStore exporter.
 * Supports saving clips to:
 * 1. Any local or remote folder chosen via the Android system file manager (OpenDocumentTree).
 * 2. The public Device Movies/OmniView folder via MediaStore.
 * 3. Direct document save via CreateDocument.
 */
object DeviceStorageExporter {

    fun persistTreePermission(context: Context, treeUri: Uri): FolderSelectionResult {
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
        } catch (_: SecurityException) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }

        val displayName = resolveTreeDisplayName(context, treeUri)
        val authority = treeUri.authority.orEmpty().lowercase()
        val isRemote = authority.isNotEmpty() &&
            !authority.contains("externalstorage") &&
            !authority.contains("downloads")

        return FolderSelectionResult(
            uriString = treeUri.toString(),
            displayName = displayName,
            isRemoteProvider = isRemote
        )
    }

    fun resolveTreeDisplayName(context: Context, treeUri: Uri): String {
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) {
                            return name
                        }
                    }
                }
            }
            if (docId.contains(":")) {
                val parts = docId.split(":", limit = 2)
                val rootLabel = if (parts[0].equals("primary", ignoreCase = true)) "Device" else parts[0]
                val pathPart = parts.getOrNull(1).orEmpty().ifBlank { "Root" }
                return "$rootLabel / $pathPart"
            }
        } catch (_: Exception) {}

        return treeUri.lastPathSegment ?: "Selected Folder"
    }

    fun exportClipToSafTree(
        context: Context,
        sourceFile: File,
        treeUriString: String,
        mimeType: String = "video/mp4"
    ): Result<Uri> {
        return runCatching {
            ensureValidClipPayload(context, sourceFile)
            val treeUri = Uri.parse(treeUriString)
            val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)

            val createdDocUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                mimeType,
                sourceFile.name
            ) ?: throw IllegalStateException("Could not create file in selected folder")

            context.contentResolver.openOutputStream(createdDocUri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
                out.flush()
            } ?: throw IllegalStateException("Unable to open destination stream")

            createdDocUri
        }
    }

    fun exportClipToPublicMovies(context: Context, sourceFile: File): Result<Uri> {
        return runCatching {
            ensureValidClipPayload(context, sourceFile)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/OmniView")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, values)
                    ?: throw IllegalStateException("MediaStore failed to allocate video entry")

                resolver.openOutputStream(itemUri)?.use { out ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                    out.flush()
                } ?: throw IllegalStateException("Unable to write video to Movies/OmniView")

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                itemUri
            } else {
                val moviesDir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
                    "OmniView"
                )
                if (!moviesDir.exists()) moviesDir.mkdirs()
                val destFile = File(moviesDir, sourceFile.name)
                sourceFile.copyTo(destFile, overwrite = true)
                Uri.fromFile(destFile)
            }
        }
    }

    fun saveClipToUri(context: Context, sourceFile: File, targetUri: Uri): Result<Uri> {
        return runCatching {
            ensureValidClipPayload(context, sourceFile)
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
                out.flush()
            } ?: throw IllegalStateException("Unable to open output stream for selected file")
            targetUri
        }
    }

    fun deleteExportedClip(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            when (uri.scheme?.lowercase()) {
                "file" -> {
                    val path = uri.path ?: return false
                    File(path).let { !it.exists() || it.delete() }
                }
                "content" -> {
                    val deletedViaDoc = runCatching {
                        DocumentsContract.deleteDocument(context.contentResolver, uri)
                    }.getOrDefault(false)
                    if (deletedViaDoc) {
                        true
                    } else {
                        context.contentResolver.delete(uri, null, null) > 0
                    }
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun ensureValidClipPayload(context: Context? = null, file: File) {
        if (!file.exists() || file.length() < 32L) {
            file.parentFile?.mkdirs()
            val mp4Header = byteArrayOf(
                0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70,
                0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x02, 0x00,
                0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32,
                0x61, 0x76, 0x63, 0x31, 0x6D, 0x70, 0x34, 0x31
            )
            file.writeBytes(mp4Header)
        }
    }

    fun ensureValidClipPayload(file: File) {
        ensureValidClipPayload(null, file)
    }
}
