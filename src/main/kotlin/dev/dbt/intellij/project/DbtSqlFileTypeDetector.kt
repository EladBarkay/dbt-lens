package dev.dbt.intellij.project

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.io.ByteSequence
import com.intellij.openapi.vfs.VirtualFile
import dev.dbt.intellij.lang.DbtFileType

/** Fallback for unclaimed SQL in free IDEs. Known SQL/TextMate associations use the substitutor. */
class DbtSqlFileTypeDetector : FileTypeRegistry.FileTypeDetector {
    override fun detect(file: VirtualFile, firstBytes: ByteSequence, firstCharsIfText: CharSequence?) =
        if (!file.isValid || !file.extension.equals("sql", ignoreCase = true) || firstCharsIfText == null) null
        else DbtDirectories.root(file.parent, null)?.let { DbtFileType.INSTANCE }

}

/** The free IDE also ships a legacy SQL syntax table, which has no Language to substitute. */
class DbtSqlFileTypeOverrider : FileTypeOverrider {
    override fun getOverriddenFileType(file: VirtualFile): com.intellij.openapi.fileTypes.FileType? {
        if (!file.isValid || file.isDirectory || !file.extension.equals("sql", ignoreCase = true)) return null
        // Filename lookup avoids recursively asking for this VirtualFile's overridden type.
        val host = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
        if (host is LanguageFileType || host.isBinary) return null
        return DbtDirectories.root(file.parent, null)?.let { DbtFileType.INSTANCE }
    }
}

internal object DbtDirectories {
    private val generatedDirectories = setOf("target", "logs", ".git", ".venv", "venv", "node_modules")
    fun root(directory: VirtualFile?, boundary: VirtualFile?): VirtualFile? {
        var current = directory
        while (current != null) {
            ProgressManager.checkCanceled()
            if (!current.isValid) return null
            if (current.name in generatedDirectories) return null
            val config = current.findChild("dbt_project.yml")
            if (config != null && !config.isDirectory) return current
            if (current == boundary) return null
            current = current.parent
        }
        return null
    }
}
