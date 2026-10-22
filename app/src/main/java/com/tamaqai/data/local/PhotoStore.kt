package com.tamaqai.data.local

import android.content.Context
import java.io.File

class PhotoStore internal constructor(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "pending_photos"))
    init { directory.mkdirs() }
    fun save(uid: String, jpeg: ByteArray): String {
        val name = "$uid.jpg"
        val target = resolve(name)
        val temporary = File(directory, "$name.tmp")
        temporary.writeBytes(jpeg)
        check(temporary.renameTo(target)) { "ui_error_043" }
        return name
    }
    fun read(name: String): ByteArray {
        val file = resolve(name)
        check(file.exists() && System.currentTimeMillis() - file.lastModified() <= RETENTION_MS) { "ui_error_044" }
        check(file.length() <= 2 * 1024 * 1024) { "ui_error_045" }
        return file.readBytes()
    }
    fun delete(name: String?) { if (name != null) resolve(name).delete() }
    fun clear() { directory.listFiles()?.forEach { check(it.delete()) } }
    fun availableFile(name: String?): File? = name?.let { resolve(it) }?.takeIf {
        it.exists() && System.currentTimeMillis() - it.lastModified() <= RETENTION_MS
    }
    fun expire() { directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > RETENTION_MS }?.forEach { it.delete() } }
    private fun resolve(name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9-]+\\.jpg")))
        return File(directory, name)
    }
    companion object { const val RETENTION_MS = 7 * 24 * 60 * 60 * 1000L }
}
