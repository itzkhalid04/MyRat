package sn.file.recover.utils

enum class FileType(val extensions: List<String>) {
    IMAGE(listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")),
    VIDEO(listOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "3gp")),
    AUDIO(listOf("mp3", "wav", "aac", "flac", "ogg", "m4a"))
}