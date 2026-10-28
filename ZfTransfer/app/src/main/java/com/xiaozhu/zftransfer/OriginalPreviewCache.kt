package com.xiaozhu.zftransfer

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** Private previews are never MediaStore imports or download-history evidence. */
class OriginalPreviewCache(private val root: File, val budget: Long = 192L * 1024 * 1024) {
    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, "$hash.jpg")
    }
    @Synchronized fun find(key: String, size: Long): File? = file(key).takeIf {
        size > 0 && it.isFile && it.length() == size
    }?.also { it.setLastModified(System.currentTimeMillis()) }

    @Synchronized fun store(key: String, size: Long, write: (OutputStream) -> Unit): File {
        require(size in 1..budget) { "照片过大，请先导入原图后预览" }
        check(root.isDirectory || root.mkdirs()) { "无法创建预览缓存" }
        val temporary = File.createTempFile("preview_", ".part", root)
        try {
            temporary.outputStream().use(write)
            check(temporary.length() == size) { "高清预览文件不完整" }
            val target = file(key)
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            var total = root.listFiles().orEmpty().filter { it.extension == "jpg" }.sumOf { it.length() }
            root.listFiles().orEmpty().filter { it.extension == "jpg" && it != target }
                .sortedBy { it.lastModified() }.forEach {
                    if (total > budget) { val length = it.length(); if (it.delete()) total -= length }
                }
            return target
        } finally { temporary.delete() }
    }
}
