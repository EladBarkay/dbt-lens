package dev.dbt.intellij.editor

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import dev.dbt.intellij.lang.DbtLexer
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.*
import dev.dbt.intellij.semantic.*

class DbtCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val source = parameters.originalFile.viewProvider.contents
                    val prefixStart = parameters.offset - result.prefixMatcher.prefix.length
                    val lexer = DbtLexer()
                    lexer.start(source)
                    var opener: com.intellij.psi.tree.IElementType? = null
                    val tokens = mutableListOf<String>()
                    while (lexer.tokenType != null && lexer.tokenStart < prefixStart) {
                        when (lexer.tokenType) {
                            T.EXPR_OPEN,
                            T.STMT_OPEN -> {
                                opener = lexer.tokenType
                                tokens.clear()
                            }
                            T.EXPR_CLOSE,
                            T.STMT_CLOSE -> {
                                opener = null
                                tokens.clear()
                            }
                            T.STRING,
                            T.COMMENT,
                            T.TEXT -> if (prefixStart < lexer.tokenEnd) return
                            else ->
                                if (lexer.tokenType != TokenType.WHITE_SPACE)
                                    tokens +=
                                        source
                                            .subSequence(lexer.tokenStart, lexer.tokenEnd)
                                            .toString()
                        }
                        lexer.advance()
                    }
                    // At the start of a string/comment token no code completion is appropriate
                    // either.
                    if (
                        lexer.tokenStart == prefixStart &&
                            lexer.tokenType in setOf(T.STRING, T.COMMENT, T.TEXT)
                    )
                        return
                    if (opener == null) return
                    val position = parameters.position
                    val variable =
                        PsiTreeUtil.getParentOfType(position, DbtVariable::class.java, false)
                    val access = variable?.let(DbtCallables::memberAccess)
                    if (tokens.lastOrNull() == "." || access != null) {
                        if (access != null) {
                            DbtSymbols.members(DbtTypeInference.of(access.firstChild)).forEach {
                                add(it, result)
                            }
                            val receiver = access.firstChild
                            if (
                                receiver is DbtVariable &&
                                    LocalVariables.candidates(receiver).isEmpty() &&
                                    ProjectMacros.hasNamespace(
                                        parameters.originalFile,
                                        receiver.text,
                                    )
                            ) {
                                addMacros(
                                    ProjectMacros.completion(
                                        parameters.originalFile,
                                        result.prefixMatcher.prefix,
                                        receiver.text,
                                    ),
                                    result,
                                )
                            }
                        }
                        return
                    }
                    if (
                        tokens.lastOrNull() == "|" ||
                            tokens.lastOrNull() == "is" ||
                            tokens.takeLast(2) == listOf("is", "not")
                    ) {
                        val filters = tokens.lastOrNull() == "|"
                        (if (filters) DbtSymbols.FILTERS else DbtSymbols.TESTS).values.forEach {
                            add(it, result)
                        }
                        if (filters)
                            addMacros(
                                ProjectMacros.completion(
                                        parameters.originalFile,
                                        result.prefixMatcher.prefix,
                                    )
                                    .filter { it.name !in DbtSymbols.FILTERS },
                                result,
                            )
                        return
                    }
                    if (opener == T.STMT_OPEN && tokens.isEmpty()) {
                        DbtLexer.KEYWORDS.filter {
                                it !in
                                    setOf(
                                        "true",
                                        "false",
                                        "True",
                                        "False",
                                        "none",
                                        "None",
                                        "and",
                                        "or",
                                        "not",
                                        "is",
                                        "in",
                                    )
                            }
                            .forEach {
                                result.addElement(
                                    LookupElementBuilder.create(it)
                                        .withTypeText("Jinja tag")
                                        .withInsertHandler { insertion, _ ->
                                            DbtBlockTemplates.insert(it, insertion)
                                        },
                                )
                            }
                        return
                    }
                    val locals = LocalVariables.visible(position)
                    locals.forEach { (name, bindings) ->
                        result.addElement(
                            LookupElementBuilder.create(bindings.first(), name)
                                .withTypeText(DbtTypeInference.of(bindings.first()).name, true),
                        )
                    }
                    DbtTypeInference.contextual(position)
                        .values
                        .filter { it.name !in locals }
                        .forEach { add(it, result) }
                    val localMacros =
                        LocalMacros.declarations(parameters.originalFile).filter {
                            it.name !in locals
                        }
                    addMacros(localMacros, result)
                    val macros =
                        ProjectMacros.completion(
                                parameters.originalFile,
                                result.prefixMatcher.prefix,
                            )
                            .filter {
                                it.name !in locals &&
                                    localMacros.none { local -> local.name == it.name }
                            }
                    addMacros(macros, result)
                    val macroNames = (localMacros + macros).mapNotNull { it.name }.toSet()
                    DbtSymbols.CONTEXT.values
                        .filter { it.name !in locals && it.name !in macroNames }
                        .forEach { add(it, result) }
                    addKeywordArguments(position, prefixStart, result)
                }
            },
        )
    }

    private fun add(symbol: DbtSymbol, result: CompletionResultSet) {
        result.addElement(
            LookupElementBuilder.create(symbol.name)
                .withTailText(symbol.signature?.let { "($it)" }.orEmpty(), true)
                .withTypeText(symbol.type.name.takeUnless { it == "unknown" }.orEmpty(), true)
                .withStrikeoutness(symbol.deprecated)
                .withInsertHandler { context, _ ->
                    if (symbol.signature != null) insertCall(context, symbol.signature.isNotBlank())
                },
        )
    }

    private fun addMacros(macros: List<DbtMacro>, result: CompletionResultSet) {
        macros.forEach { macro ->
            macro.name?.let {
                result.addElement(
                    LookupElementBuilder.create(macro, it)
                        .withTailText("(${macro.parameters.joinToString()})", true)
                        .withTypeText(macro.containingFile.name, true)
                        .withInsertHandler { context, _ ->
                            insertCall(context, macro.parameters.isNotEmpty())
                        },
                )
            }
        }
    }

    private fun addKeywordArguments(
        position: PsiElement,
        prefixStart: Int,
        result: CompletionResultSet,
    ) {
        val argument =
            generateSequence(position) { if (it is com.intellij.psi.PsiFile) null else it.parent }
                .firstOrNull { it.node?.elementType == T.ARGUMENT } ?: return
        val call = argument.parent.takeIf { it.node.elementType == T.CALL } ?: return
        // Only at the beginning of an argument, never after its '=' or within an expression.
        if (argument.textOffset != prefixStart) return
        val callee = call.firstChild
        val variable = (callee as? DbtVariable) ?: callee.lastChild as? DbtVariable ?: return
        val macro = variable.reference?.resolve() as? DbtMacro
        val names = macro?.parameters ?: DbtTypeInference.symbol(variable)?.parameters.orEmpty()
        val otherArgs = call.children.filter { it != argument && it.node.elementType == T.ARGUMENT }
        val used =
            otherArgs.mapNotNull { it.node.findChildByType(T.ARGUMENT_NAME)?.text }.toMutableSet()
        val positional =
            macro?.parameters
                ?: DbtTypeInference.symbol(variable)
                    ?.signature
                    ?.split(',')
                    ?.takeWhile { !it.trim().startsWith('*') }
                    ?.map { it.trim().substringBefore('=').trim() }
                    ?.filter { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) }
                    .orEmpty()
        if (
            otherArgs.none { arg -> arg.node.getChildren(null).any { it.text in setOf("*", "**") } }
        ) {
            val supplied = otherArgs.count {
                it.textOffset < argument.textOffset &&
                    it.node.findChildByType(T.ARGUMENT_NAME) == null
            }
            used += positional.take(supplied)
        }
        names
            .filter { it !in used }
            .forEach { name ->
                result.addElement(
                    LookupElementBuilder.create("$name=")
                        .withLookupString(name)
                        .withTypeText("keyword argument", true)
                        .withInsertHandler { context, _ ->
                            if (context.document.charsSequence.getOrNull(context.tailOffset) == '=')
                                context.document.deleteString(
                                    context.tailOffset,
                                    context.tailOffset + 1,
                                )
                        },
                )
            }
    }

    private fun insertCall(context: InsertionContext, hasArguments: Boolean) {
        if (context.completionChar == '(') context.setAddCompletionChar(false)
        val offset = context.tailOffset
        val next =
            context.document.charsSequence
                .substring(offset)
                .dropWhile { it == ' ' || it == '\t' }
                .firstOrNull()
        if (next == '(') return
        context.document.insertString(offset, "()")
        context.editor.caretModel.moveToOffset(offset + if (hasArguments) 1 else 2)
    }
}
