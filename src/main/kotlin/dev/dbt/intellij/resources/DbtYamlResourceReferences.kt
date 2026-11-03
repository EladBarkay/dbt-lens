package dev.dbt.intellij.resources

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import dev.dbt.intellij.config.DbtYamlFiles
import dev.dbt.intellij.lang.DbtFileType
import dev.dbt.intellij.lang.DbtTypes as T
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/** Links in native YAML strings, including exposures' unwrapped depends_on calls. */
class DbtYamlResourceReferences : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext,
                ): Array<PsiReference> {
                    val scalar = element as YAMLScalar
                    val file = element.containingFile.virtualFile ?: return PsiReference.EMPTY_ARRAY
                    if (DbtYamlFiles.schema(element.project, file) == null)
                        return PsiReference.EMPTY_ARRAY
                    val dependency =
                        generateSequence(element.parent) { it.parent }
                            .filterIsInstance<YAMLKeyValue>()
                            .any { it.keyText == "depends_on" }
                    if (!dependency && !scalar.textValue.contains("{{"))
                        return PsiReference.EMPTY_ARRAY
                    val range = ElementManipulators.getValueTextRange(scalar)
                    val escaper = scalar.createLiteralTextEscaper()
                    val decoded = StringBuilder()
                    if (!escaper.decode(range, decoded)) return PsiReference.EMPTY_ARRAY
                    val text = decoded.toString()
                    val references = mutableListOf<PsiReference>()
                    val prefix = if (dependency && !text.contains("{{")) "{{ " else ""
                    val code = if (prefix.isEmpty()) text else "$prefix$text }}"
                    val parsed =
                        PsiFileFactory.getInstance(element.project)
                            .createFileFromText("reference.jinja", DbtFileType.INSTANCE, code)
                    // Parse the complete expression so strings/comments containing call-looking
                    // text
                    // never turn into links. Native YAML remains the host language.
                    for (call in
                        PsiTreeUtil.collectElements(parsed) { it.node?.elementType == T.CALL }) {
                        val resource = ResourceCall.from(call) ?: continue
                        val startInHost =
                            escaper.getOffsetInHost(
                                call.textRange.startOffset - prefix.length,
                                range,
                            )
                        val endInHost =
                            escaper.getOffsetInHost(call.textRange.endOffset - prefix.length, range)
                        if (startInHost >= 0 && endInHost > startInHost)
                            references +=
                                DbtResourceReference(
                                    element,
                                    TextRange(startInHost, endInHost),
                                    resource,
                                )
                    }
                    return references.toTypedArray()
                }
            },
        )
    }
}
