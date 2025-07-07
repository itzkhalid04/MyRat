package sn.file.recover.model

data class RecoveredFile(
    val path: String,
    val name: String,
    val size: Long,
    val recoveredDate: Long,
    val mimeType: String
)