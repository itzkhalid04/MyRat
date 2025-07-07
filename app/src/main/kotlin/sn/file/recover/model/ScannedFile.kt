package sn.file.recover.model

data class ScannedFile(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val isSelected: Boolean = false
)