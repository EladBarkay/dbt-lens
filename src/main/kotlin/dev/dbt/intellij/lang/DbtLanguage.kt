package dev.dbt.intellij.lang

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.tree.OuterLanguageElementType
import javax.swing.Icon

object DbtLanguage : Language("dbt Jinja")

class DbtFileType private constructor() : LanguageFileType(DbtLanguage) {
    override fun getName() = "dbt Jinja"
    override fun getDescription() = "dbt SQL/Jinja template"
    override fun getDefaultExtension() = "jinja"
    override fun getIcon(): Icon {
        val sql = FileTypeManager.getInstance().getFileTypeByExtension("sql")
        return sql.takeIf { it !== this && (it.name == "SQL" || it.defaultExtension == "sql") }?.icon ?: SQL_ICON
    }
    companion object {
        @JvmField val INSTANCE = DbtFileType()
        private val SQL_ICON = IconLoader.getIcon("/icons/sql.svg", DbtFileType::class.java)
    }
}

object DbtTypes {
    private fun type(name: String) = IElementType(name, DbtLanguage)
    val FILE = IFileElementType(DbtLanguage)
    val TEXT = type("SQL_TEXT")
    val EXPR_OPEN = type("{{")
    val EXPR_CLOSE = type("}}")
    val STMT_OPEN = type("{%")
    val STMT_CLOSE = type("%}")
    val COMMENT = type("JINJA_COMMENT")
    val IDENTIFIER = type("IDENTIFIER")
    val KEYWORD = type("KEYWORD")
    val STRING = type("STRING")
    val NUMBER = type("NUMBER")
    val OPERATOR = type("OPERATOR")
    val LPAREN = type("("); val RPAREN = type(")")
    val LBRACKET = type("["); val RBRACKET = type("]")
    val LBRACE = type("{"); val RBRACE = type("}")
    val COMMA = type(","); val DOT = type("."); val COLON = type(":")
    val EXPRESSION = type("EXPRESSION")
    val STATEMENT = type("STATEMENT")
    val BLOCK = type("BLOCK")
    val MACRO = type("MACRO")
    val DECLARATION_NAME = type("DECLARATION_NAME")
    val PARAMETER_NAME = type("PARAMETER_NAME")
    val BINDING_NAME = type("BINDING_NAME")
    val VARIABLE = type("VARIABLE")
    val ACCESS = type("ACCESS")
    val CALL = type("CALL")
    val ARGUMENT = type("ARGUMENT")
    val VALUE = type("VALUE")
    val BINARY = type("BINARY")
    val UNARY = type("UNARY")
    val SQL_KEYWORD = type("SQL_KEYWORD")
    val SQL_STRING = type("SQL_STRING")
    val SQL_COMMENT = type("SQL_COMMENT")
    val SQL_NUMBER = type("SQL_NUMBER")
    val OUTER = OuterLanguageElementType("JINJA_OUTER", DbtLanguage)
    val COMMENTS = TokenSet.create(COMMENT)
    val STRINGS = TokenSet.create(STRING)
    val OPENERS = TokenSet.create(EXPR_OPEN, STMT_OPEN)
}
