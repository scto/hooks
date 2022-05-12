package com.intuit.hooks.plugin.ksp

import arrow.core.sequenceValidated
import arrow.core.valueOr
import arrow.typeclasses.Semigroup
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.intuit.hooks.plugin.validation.annotationDslMarkers
import com.intuit.hooks.plugin.codegen.HookCodeGen
import com.intuit.hooks.plugin.validation.validateHook
import com.pinterest.ktlint.core.KtLint
import com.pinterest.ktlint.core.KtLint.format
import com.pinterest.ktlint.core.api.FeatureInAlphaState
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider

public class HooksProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        resolver.getNewFiles().forEach {
            it.accept(HooksVisitor(), Unit)
        }

        return emptyList()
    }

    private inner class HooksVisitor : KSVisitorVoid() {

        override fun visitFile(file: KSFile, data: Unit) {
            file.declarations.filter {
                it is KSClassDeclaration
            }.forEach {
                it.accept(this, Unit)
            }
        }

        @OptIn(FeatureInAlphaState::class)
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            // TODO: This should really be restructured to follow KSP visitor pattern for members
            val superTypeNames = classDeclaration.superTypes
                .map(KSTypeReference::element)
                .filterIsInstance<KSClassifierReference>()
                .map(KSClassifierReference::referencedName)

            // TODO: Account for import aliases :P + how to avoid false positives?
            if (!superTypeNames.contains("Hooks") && !superTypeNames.contains("HooksDsl")) {
                classDeclaration.declarations.filter {
                    it is KSClassDeclaration && it.validate()
                }.forEach {
                    it.accept(this, Unit)
                }
            } else classDeclaration.findHooks().map { codeGen ->
                if (codeGen.firstOrNull() == null) return@map

                val packageName = classDeclaration.packageName
                val (classes, properties) = codeGen.map(::generateHookClass).unzip()
                val imports = createImportDirectives(classDeclaration, codeGen.toList())

                val visibility = classDeclaration.getVisibility().name.lowercase()
                val kind = classDeclaration.classKind.name.lowercase()
                val name =
                    "${classDeclaration.parentDeclaration?.simpleName?.asString() ?: ""}${classDeclaration.simpleName.asString()}Impl"
                val typeParameters = if (classDeclaration.typeParameters.isEmpty()) "" else "<${
                    classDeclaration.typeParameters.joinToString(separator = ", ") { it.simpleName.asString() }
                }>"
                val fqName = classDeclaration.qualifiedName!!.asString()

                val newSource =
                    """|${packageName.asString().takeIf(String::isNotEmpty)?.let { "package $it" } ?: ""}
                       |
                       |$imports
                       |
                       |${visibility} $kind $name$typeParameters : ${fqName}$typeParameters() {
                       |   ${properties.joinToString("\n", "\n", "\n") { it }}
                       |   ${classes.joinToString("\n", "\n", "\n") { it }} 
                       |}""".trimMargin()

                val file = codeGenerator.createNewFile(
                    Dependencies(true, classDeclaration.containingFile!!),
                    packageName.asString(),
                    name,
                )

                logger.logging("""raw generated source:
                    |$newSource
                """.trimMargin(), classDeclaration)

                KtLint.ExperimentalParams(
                    text = newSource,
                    ruleSets = listOf(StandardRuleSetProvider().get()),
                    cb = { _, _ -> }
                )
                    .let(::format)
                    .also {
                        logger.logging("""formatted generated source:
                            |$it
                        """.trimMargin(), classDeclaration)
                    }
                    .let(String::toByteArray)
                    .let(file::write)

                file.close()
            }.valueOr { errors ->
                errors.forEach { logger.error(it.message, it.symbol) }
            }
        }
    }

    private fun KSClassDeclaration.findHooks() = getAllProperties()
        .filter {
            // Only generate hooks for properties that use our annotation
            it.annotations.map(KSAnnotation::shortName)
                .map(KSName::asString)
                .filter(annotationDslMarkers::contains)
                .toList()
                .isNotEmpty()
        }
        .map(::validateHook)
        .sequenceValidated(Semigroup.nonEmptyList())

    private fun generateHookClass(hookCodeGen: HookCodeGen): Pair<String, String> {
        val classDefinition = hookCodeGen.generateClass()
        val propertyDefinition = hookCodeGen.generateProperty()

        return classDefinition to propertyDefinition
    }

    private fun createImportDirectives(classDeclaration: KSClassDeclaration, codeGens: List<HookCodeGen>): String {
        val existingImports = emptyList<String>() // TODO: Get imports -- this might be fixed with kotlin poet?
//        classDeclaration.containingFile?.
//        ?.removeHooksDslImport()
//        ?.map { it.text ?: "" }
//        ?: emptyList()

        val newImports = codeGens.flatMap { it.generateImports() }

        val hookStarImport = listOf("import com.intuit.hooks.*")

        return (hookStarImport + existingImports + newImports)
            .distinct()
            .joinToString("\n")
    }

    public class Provider : SymbolProcessorProvider {

        override fun create(environment: SymbolProcessorEnvironment): HooksProcessor = HooksProcessor(
            environment.codeGenerator,
            environment.logger,
        )
    }

    public class Exception(message: String, cause: Throwable? = null) : kotlin.Exception(message, cause)
}
