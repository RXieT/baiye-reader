package io.github.rxiet.baiye.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import io.github.rxiet.baiye.data.BackgroundPreset
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Imported resources are copied into app storage; arbitrary URI/path access is never exposed to the reader. */
class ReaderMediaStore(private val context: Context) {
    private val root = File(context.filesDir, "reader-media").apply { mkdirs() }
    private val fonts = File(root, "fonts").apply { mkdirs() }
    private val backgrounds = File(root, "backgrounds").apply { mkdirs() }

    fun fontFile(name: String): File? = ownedFile(fonts, name)
    fun backgroundFile(name: String): File? = ownedFile(backgrounds, name)

    suspend fun importFont(uri: Uri): ImportedFont = withContext(Dispatchers.IO) {
        val temp = copyBounded(uri, fonts, 32L * 1024 * 1024)
        try {
            val magic = temp.inputStream().use { it.readNBytes(4) }
            require(magic.size == 4 && (magic.contentEquals(byteArrayOf(0, 1, 0, 0)) ||
                String(magic, Charsets.ISO_8859_1) in setOf("OTTO", "ttcf", "true"))) { "不支持的字体" }
            Typeface.Builder(temp).build()
            val suffix = if (String(magic, Charsets.ISO_8859_1) == "OTTO") "otf" else if (String(magic, Charsets.ISO_8859_1) == "ttcf") "ttc" else "ttf"
            val name = "font-${digest(temp)}.$suffix"
            val destination = File(fonts, name)
            if (!destination.exists()) check(temp.renameTo(destination)) else temp.delete()
            ImportedFont(name, displayName(uri).substringBeforeLast('.').take(40).ifBlank { "自定义字体" })
        } finally { temp.delete() }
    }

    suspend fun importBackground(uri: Uri): BackgroundPreset = withContext(Dispatchers.IO) {
        val temp = copyBounded(uri, backgrounds, 24L * 1024 * 1024)
        var bitmap: Bitmap? = null
        try {
            val source = ImageDecoder.createSource(temp)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val factor = (1600f / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
                decoder.setTargetSize((info.size.width * factor).toInt().coerceAtLeast(1), (info.size.height * factor).toInt().coerceAtLeast(1))
            }
            bitmap = decoded
            val name = "background-${digest(temp)}.webp"
            val destination = File(backgrounds, name)
            if (!destination.exists()) {
                val output = File.createTempFile("background-", ".tmp", backgrounds)
                try {
                    output.outputStream().use { check(decoded.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, it)) }
                    check(output.renameTo(destination))
                } finally { output.delete() }
            }
            var brightness = 0.0
            for (y in 0 until 6) for (x in 0 until 6) {
                val pixel = decoded.getPixel((decoded.width - 1) * x / 5, (decoded.height - 1) * y / 5)
                brightness += (((pixel shr 16) and 255) * .2126 + ((pixel shr 8) and 255) * .7152 + (pixel and 255) * .0722) / 255.0
            }
            BackgroundPreset(name.removeSuffix(".webp"), displayName(uri).substringBeforeLast('.').take(32).ifBlank { "自定义背景" }, name, brightness / 36 < .42)
        } finally { bitmap?.recycle(); temp.delete() }
    }

    private fun copyBounded(uri: Uri, directory: File, limit: Long): File {
        val temp = File.createTempFile("import-", ".tmp", directory)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= limit) { "文件过大" }
                        output.write(buffer, 0, count)
                    }
                    require(total > 0)
                }
            } ?: error("无法打开文件")
            return temp
        } catch (failure: Throwable) { temp.delete(); throw failure }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull().orEmpty()

    private fun ownedFile(directory: File, name: String): File? {
        if (!name.matches(Regex("(?:font|background)-[a-f0-9]{24}\\.(?:ttf|otf|ttc|webp)"))) return null
        val file = File(directory, name).canonicalFile
        return file.takeIf { it.parentFile == directory.canonicalFile && it.isFile }
    }
}

data class ImportedFont(val file: String, val label: String)
