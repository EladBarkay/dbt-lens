package dev.dbt.intellij.sql

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.templateLanguages.TemplateDataElementType
import com.intellij.psi.templateLanguages.TemplateLanguageFileViewProvider
import dev.dbt.intellij.lang.DbtLanguage
import dev.dbt.intellij.lang.DbtFileType
import dev.dbt.intellij.lang.DbtTypes
import dev.dbt.intellij.psi.DbtFile

/** Opt-in experiment. Shipping behavior remains the source-preserving lexical SQL fallback. */
class DbtSqlViewProviderFactory : FileViewProviderFactory {
    override fun createFileViewProvider(file: VirtualFile, language: Language, manager: PsiManager, eventSystemEnabled: Boolean): FileViewProvider {
        // The SQL base parser cannot create a SqlFile from its base Language; a concrete dialect is required.
        val sql = Language.findLanguageByID("PostgreSQL")
        if (!java.lang.Boolean.getBoolean("dbt.jinja.experimental.sql") || sql == null ||
            LanguageParserDefinitions.INSTANCE.forLanguage(sql) == null) {
            return SingleRootFileViewProvider(manager, file, eventSystemEnabled, DbtFileType.INSTANCE)
        }
        return DbtSqlViewProvider(manager, file, eventSystemEnabled, sql)
    }
}

class DbtSqlViewProvider(manager: PsiManager, file: VirtualFile, physical: Boolean, private val sql: Language) :
    MultiplePsiFilesPerDocumentFileViewProvider(manager, file, physical), TemplateLanguageFileViewProvider {
    override fun getBaseLanguage() = DbtLanguage
    override fun getTemplateDataLanguage() = sql
    override fun getLanguages(): Set<Language> = setOf(DbtLanguage, sql)
    override fun createFile(language: Language): PsiFile? = when (language) {
        DbtLanguage -> DbtFile(this)
        sql -> LanguageParserDefinitions.INSTANCE.forLanguage(sql)?.createFile(this)?.also {
            (it as? PsiFileImpl)?.setContentElementType(SQL_DATA)
        }
        else -> null
    }
    override fun cloneInner(fileCopy: VirtualFile): MultiplePsiFilesPerDocumentFileViewProvider =
        DbtSqlViewProvider(manager, fileCopy, false, sql)
    companion object {
        // Removing holes is intentionally insufficient for generated identifiers/relations.
        // Tests and the feasibility record must establish limits before any production enablement.
        private val SQL_DATA = TemplateDataElementType("DBT_SQL_TEMPLATE_DATA", DbtLanguage, DbtTypes.TEXT, DbtTypes.OUTER)
    }
}
