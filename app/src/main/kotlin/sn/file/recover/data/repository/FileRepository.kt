package sn.file.recover.data.repository

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sn.file.recover.model.RecoveredFile
import sn.file.recover.model.ScannedFile
import sn.file.recover.utils.FileType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val recoveredDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "RecoveredFiles"
    )
    
    init {
        if (!recoveredDir.exists()) {
            recoveredDir.mkdirs()
        }
    }
    
    suspend fun scanForFiles(fileType: FileType): List<ScannedFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<ScannedFile>()
        
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        
        val selection = buildString {
            append("(")
            fileType.extensions.forEachIndexed { index, ext ->
                if (index > 0) append(" OR ")
                append("${MediaStore.Files.FileColumns.DATA} LIKE '%.$ext'")
            }
            append(")")
        }
        
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            null,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataColumn)
                val file = File(path)
                
                if (file.exists() && file.length() > 0) {
                    files.add(
                        ScannedFile(
                            path = path,
                            name = cursor.getString(nameColumn),
                            size = cursor.getLong(sizeColumn),
                            lastModified = cursor.getLong(dateColumn),
                            mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream"
                        )
                    )
                }
            }
        }
        
        files
    }
    
    suspend fun recoverFiles(files: List<ScannedFile>) = withContext(Dispatchers.IO) {
        files.forEach { scannedFile ->
            val sourceFile = File(scannedFile.path)
            val destinationFile = File(recoveredDir, scannedFile.name)
            
            if (sourceFile.exists()) {
                sourceFile.copyTo(destinationFile, overwrite = true)
            }
        }
    }
    
    suspend fun getRecoveredFiles(): List<RecoveredFile> = withContext(Dispatchers.IO) {
        if (!recoveredDir.exists()) return@withContext emptyList()
        
        recoveredDir.listFiles()?.mapNotNull { file ->
            if (file.isFile) {
                RecoveredFile(
                    path = file.absolutePath,
                    name = file.name,
                    size = file.length(),
                    recoveredDate = file.lastModified(),
                    mimeType = getMimeType(file.extension)
                )
            } else null
        } ?: emptyList()
    }
    
    suspend fun deleteRecoveredFile(file: RecoveredFile) = withContext(Dispatchers.IO) {
        File(file.path).delete()
    }
    
    private fun getMimeType(extension: String): String {
        return when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "avi" -> "video/avi"
            "mkv" -> "video/mkv"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}