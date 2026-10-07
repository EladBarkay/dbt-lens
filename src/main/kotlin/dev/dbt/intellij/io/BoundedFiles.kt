package dev.dbt.intellij.io

import com.intellij.openapi.progress.ProgressManager
import java.io.FilterInputStream
import java.io.IOException
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path

/** Enforces the byte limit during reading, including files that grow after the initial stat. */
internal object BoundedFiles {
    fun reader(file: Path, limit: Long): Reader {
        require(Files.size(file) <= limit) { "File exceeds the $limit byte limit" }
        return object : FilterInputStream(Files.newInputStream(file)) {
                private var consumed = 0L

                private fun count(bytes: Int): Int {
                    if (bytes > 0) consumed += bytes
                    if (consumed > limit) throw IOException("File exceeds the $limit byte limit")
                    return bytes
                }

                override fun read(): Int {
                    ProgressManager.checkCanceled()
                    val value = `in`.read()
                    count(if (value < 0) 0 else 1)
                    return value
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    ProgressManager.checkCanceled()
                    return count(
                        `in`.read(
                            buffer,
                            offset,
                            minOf(length.toLong(), limit - consumed + 1).toInt(),
                        )
                    )
                }
            }
            .reader(Charsets.UTF_8)
            .buffered()
    }

    fun text(file: Path, limit: Long): String = reader(file, limit).use { it.readText() }
}
