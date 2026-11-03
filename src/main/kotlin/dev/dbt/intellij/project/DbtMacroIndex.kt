package dev.dbt.intellij.project

import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor

/** Index template tokens independently of the host file type. Never runs dbt or project code. */
class DbtMacroIndex : ScalarIndexExtension<String>() {
    override fun getName() = NAME

    override fun getVersion() = 1

    override fun dependsOnFileContent() = true

    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter() = FileBasedIndex.InputFilter {
        it.extension?.lowercase() in setOf("sql", "jinja", "j2")
    }

    override fun getIndexer() =
        DataIndexer<String, Void, FileContent> { content ->
            dev.dbt.intellij.lang.DbtDeclarations.scan(content.contentAsText, "macro").associateTo(
                linkedMapOf<String, Void?>()
            ) {
                it.name to null
            }
        }

    companion object {
        val NAME: ID<String, Void> = ID.create("dev.dbt.jinja.macros")
    }
}
