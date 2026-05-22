package com.example

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.telegram.tlrpc.SchemeAllLayersParser
import org.telegram.tlrpc.SchemeCodeGen
import org.telegram.tlrpc.SchemeTlValidator
import org.telegram.tlrpc.models.RULES
import org.telegram.tlrpc.models.TlObjectWithLayer
import org.telegram.tlrpc.schema.TlSchemaJsonParser
import org.telegram.tlrpc.telegram.TelegramCodeParser
import org.telegram.tlrpc.telegram.TelegramTlClass
import java.io.File

abstract class GenerateSchemeTask : DefaultTask() {
    companion object {
        const val LAYER = 229;
    }


    @get:InputDirectory
    var tlSourcesDir: File = File("")

    @get:InputDirectory
    var tlSourcesDirectDir: File = File("")

    @get:InputDirectory
    var resourcesDir: File = File("")

    @get:OutputDirectory
    var outputDir: File = File("")

    @TaskAction
    fun generate() {
        println("🚀 GenerateSchemeTask started")
        println("  rootDir:       $tlSourcesDir")
        println("  resourcesDir:  $resourcesDir")
        println("  outputDir:     $outputDir")

        outputDir.mkdirs()

        val tlDirectory = File(tlSourcesDir, "tl/")
        val tlrpcFile = File(tlSourcesDir, "TLRPC.java")
        val smsjobsFile = File(tlSourcesDirectDir, "TL_smsjobs.java")

        // === Парсинг ===

        val files = tlDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .toMutableList()
            .apply {
                add(tlrpcFile)
                add(smsjobsFile)
            }

        val telegramClasses = TelegramCodeParser.parse(files)
        val tlSchemaFull = TlSchemaJsonParser.parse(resourcesDir, LAYER)
        val tlSchemaFilter = tlSchemaFull.applyRules(RULES.rules)

        val undefinedTelegramClasses = telegramClasses.groupedByConstructorAll.filterKeys {
            it !in tlSchemaFull.magicsAll
        }.values.flatten()

        File(outputDir, "not_linked_classes.txt")
            .writeText(undefinedTelegramClasses.map { it.toString() }.sorted().joinToString("\n"))

        val totalHistory = tlSchemaFull.getAllConstructorsHistory()
        val classesByUniqueIds = telegramClasses.groupedByConstructorUnique
        val schema = SchemeAllLayersParser.parseAllLayers(resourcesDir)

        val dep = RULES.rules.databaseTypes
        val legacyConstrKeys = schema.schemes.map { s ->
            val types = dep
                .mapNotNull { s.dependenciesTransitive[it] }
                .flatten()
                .toSet() + dep
            s.scheme.constructors2
                .filter { it.key.name.type in types }
                .map { it.key }
        }.flatten().toSet()

        val legacyPaths = schema.schemes.map { s ->
            val paths = dep.map { SchemeTlValidator.findShortestPaths(s.dependenciesDirect, it) }
            paths.map { it.entries }.flatten().toSet()
                .map { it.key to it.value }
                .groupBy { it.first }
                .mapValues { it.value.map { x -> x.second }.toSet().toList() }
        }

        val allNeededLegacyConstr = schema.constructorsLegacy2
            .filter { it.tl.key in legacyConstrKeys }.toSet()

        val allConstructorsDefault = schema.constructorsActual2.toSet() + allNeededLegacyConstr
        val allConstructorsDefaultKeys = allConstructorsDefault.map { it.tl.key }.toSet()
        val encryptedConstructors = schema.encryptedConstructors
            .filter { it.tl.key !in allConstructorsDefaultKeys }

        val allConstructors = allConstructorsDefault + encryptedConstructors
        val allMethods = (schema.methodsActual2 + schema.methodsLegacy2).toSet()

        val constructorTypesMap = allConstructors.groupBy { it.tl.key.name.type }
        val enumTypes = constructorTypesMap.filter { entry ->
            entry.value.none { it.tl.params.list.isNotEmpty() }
        }

        val schemaIds = allConstructors.map { it.tl.key.constructorId } +
                allMethods.map { it.tl.key.constructorId }
        val schemaAllIds = schemaIds.toSet()
        val schemaUniqueIds = schemaIds.groupingBy { it }.eachCount().filterValues { it == 1 }.keys
        val constructorsByUniqueIds = allConstructors
            .filter { it.tl.key.constructorId in schemaUniqueIds }
            .associateBy { it.tl.key.constructorId }
        val methodsByUniqueIds = allMethods
            .filter { it.tl.key.constructorId in schemaUniqueIds }
            .associateBy { it.tl.key.constructorId }

        // === Линковка ===

        val linkedObjects = mutableSetOf<Pair<TlObjectWithLayer, TelegramTlClass>>()
        linkObjectsByUniqueId(classesByUniqueIds, constructorsByUniqueIds, linkedObjects)

        val linkedConstructors = linkedObjects.toSet()
        linkObjectsByUniqueId(classesByUniqueIds, methodsByUniqueIds, linkedObjects)

        val linkedTypes = mutableSetOf<Pair<String, TelegramTlClass>>()
        linkTypesBySinglePredicate(classesByUniqueIds, constructorTypesMap, linkedTypes)
        linkTypesByLinkedObjects(telegramClasses.classes, linkedConstructors, linkedTypes)

        // === Генерация ===

        val typesForGen = allConstructors.map { it.tl.key.name.type }.toSet() +
                schema.encryptedConstructors.map { it.tl.key.name.type }

        for (type in typesForGen) {
            if (type == "Vector t") continue
            generateSchemeClassForType(type, constructorTypesMap[type]!!, encryptedConstructors.toList())
        }

        generateSchemeTestClassForType(
            allConstructors.toList(),
            encryptedConstructors.toList(),
            linkedTypes,
            legacyPaths,
            telegramClasses.classes
        )
        // println("✅ Generation complete. Output: $outputDir")
    }


    // =========================================================
    // Генерация — бывшие private fun в main.kt
    // =========================================================

    private fun generateSchemeClassForType(
        type: String,
        constructors: List<TlObjectWithLayer>,
        encrypted: List<TlObjectWithLayer>
    ) {
        val packageName = "org.telegram.tgnet.model.generated"
        val x = constructors.groupBy { it.tl.key.name.type }
            .filter { it.value.any { c -> c.layerLast == LAYER } }.keys

        val sealedClassName = "TlGen_" + type.replace('.', '_')
        val sealedClassBuilder = TypeSpec.classBuilder(sealedClassName)
            .addModifiers(KModifier.SEALED)
            .addSuperinterface(ClassName("org.telegram.tgnet.model", "TlGen_Object"))

        for (constructor in constructors) {
            var needSuper = true
            if (constructor.layerLast != LAYER
                && constructor !in encrypted
                && constructor.tl.key.name.type in x
            ) {
                needSuper = false
            }
            if (!RULES.rules.filterConstructor(constructor.tl.key.name)) continue

            sealedClassBuilder.addType(
                SchemeCodeGen.generateDataClass(
                    constructor,
                    if (needSuper) ClassName(packageName, sealedClassName) else null
                )
            )
        }

        FileSpec.builder(packageName, sealedClassName)
            .addImport("org.telegram.tgnet.model", "TlGen_Object", "TlGen_Vector")
            .addType(sealedClassBuilder.build())
            .build()
            .writeTo(outputDir)
    }

    private fun generateSchemeTestClassForType(
        constructors: List<TlObjectWithLayer>,
        encrypted: List<TlObjectWithLayer>,
        linkedTypes: Set<Pair<String, TelegramTlClass>>,
        comments: List<Map<String, List<List<String>>>>,
        allTelegramClasses: Set<TelegramTlClass>
    ) {
        val packageName = "org.telegram.tgnet.test.generated"

        val runWithAnnotation = AnnotationSpec.builder(
            ClassName("org.junit.runner", "RunWith")
        ).addMember("%T::class", ClassName("org.junit.experimental.runners", "Enclosed"))
            .build()

        val testAllBuilder = TypeSpec.classBuilder("Test_All")
            .addAnnotation(runWithAnnotation)
        val testActualBuilder = TypeSpec.classBuilder("Test_Actual")
            .superclass(ClassName("org.telegram.tgnet.test", "BaseSchemeTest"))
        val testLegacyBuilder = TypeSpec.classBuilder("Test_Legacy")
            .superclass(ClassName("org.telegram.tgnet.test", "BaseSchemeTest"))
        val testEncryptedBuilder = TypeSpec.classBuilder("Test_Encrypred")
            .superclass(ClassName("org.telegram.tgnet.test", "BaseSchemeTest"))

        val lt = linkedTypes.groupBy { it.first }.mapValues { it.value.map { it.second } }

        // Per-deserializer set of magics reachable from its static
        // `TLdeserialize` + `fromConstructor` methods. Two precise sources extracted
        // by TelegramCodeParser.extractDispatchedMagics:
        //   - `dispatchedMagicLiterals` — IntegerLiteralExpr labels inside `case ...:`
        //   - `dispatchedMagicNames` — raw `<X>.constructor` scope expressions
        //     (covers both `case X.constructor:` switch dispatch and leaf-class
        //     identity checks)
        //
        // Names resolve via a staged lookup against `allTelegramClasses`:
        //   1. exact fullName match — handles cross-package qualified scopes like
        //      `TL_legacy_message.TL_message_layer224`
        //   2. dispatcher's enclosing scope + name — handles sibling references
        //      like `TL_groupCallDiscarded` inside `TLRPC.GroupCall`
        //   3. unique simple name — last-resort fallback; deliberately drops
        //      ambiguous names rather than risking last-wins. Resulting over-skip
        //      is caught by SKIP_CEILING in `.github/workflows/tests.yml`.
        val classesByFullName = allTelegramClasses.associateBy { it.fullName }
        val simpleNameCounts = allTelegramClasses.groupingBy { it.name }.eachCount()
        val classesBySimpleName = allTelegramClasses
            .filter { simpleNameCounts[it.name] == 1 }
            .associateBy { it.name }
        fun lookupRelativeTo(name: String, dispatcher: TelegramTlClass): TelegramTlClass? {
            classesByFullName[name]?.let { return it }
            val parentPrefix = dispatcher.fullName.substringBeforeLast('.', "")
            if (parentPrefix.isNotEmpty()) {
                classesByFullName["$parentPrefix.$name"]?.let { return it }
            }
            return classesBySimpleName[name]
        }

        val reachableMagicsPerDeserializer: Map<TelegramTlClass, Set<UInt>> =
            linkedTypes.map { it.second }.toSet().associateWith { dz ->
                val fromNames = dz.dispatchedMagicNames
                    .mapNotNull { name -> lookupRelativeTo(name, dz)?.constructor }
                    .toSet()
                dz.dispatchedMagicLiterals + fromNames
            }

        val constructorsSorted = constructors.sortedWith(
            compareByDescending<TlObjectWithLayer> { it.layerLast }
                .thenBy { it.tl.key.name.type }
                .thenBy { it.tl.key.name.predicate })

        var testIndex = 0;

        for (constructor in constructorsSorted) {
            if (!RULES.rules.filterConstructor(constructor.tl.key.name)) continue

            val isEncrypted = constructor in encrypted
            val isLegacy = constructor.layerLast < LAYER
            val type = constructor.tl.key.name.type.replace('.', '_')
            val name = "test_${(testIndex++).toString().padStart(6, '0')}_${type}_${constructor.codegenDataClassName}"

            val linkedDeserializers = lt[constructor.tl.key.name.type]

            // Per-deserializer: emit the real test call when the magic is in
            // that deserializer's reachable set, otherwise emit assumeTrue.
            // Preserves coverage of deserializers that DO still route the
            // magic when others have dropped it.
            val perDeserializerLines = linkedDeserializers?.map { clz ->
                val magicReachable = !isLegacy || isEncrypted ||
                    constructor.tl.key.constructorId in reachableMagicsPerDeserializer[clz].orEmpty()
                if (magicReachable) {
                    val clz2 = clz.packageName + "." + clz.fullName
                    "test_TLdeserialize(org.telegram.tgnet.model.generated.TlGen_${type}.${constructor.codegenDataClassName}::class, " +
                            "${clz2}::TLdeserialize, ${if (isLegacy && !isEncrypted) constructor.layerLast.toString() else "null"})"
                } else {
                    "assumeTrue(\"Test skipped, magic dropped from dispatcher\", false)"
                }
            }

            // When every line is a skip the @Test method does nothing, so
            // collapse to a single skip statement; otherwise emit the mixed
            // list (real-call + skip lines coexist — the test runs the calls
            // and stops on the first assumeTrue, which is fine because the
            // call lines are independent test_TLdeserialize invocations and
            // each opens with `buffer.rewind()`).
            val lines = when {
                perDeserializerLines == null ->
                    listOf("assumeTrue(\"Test skipped, link error\", false)")
                perDeserializerLines.all { it.startsWith("assumeTrue(") } ->
                    listOf("assumeTrue(\"Test skipped, magic dropped from dispatcher\", false)")
                else -> perDeserializerLines.filterNot { it.startsWith("assumeTrue(") }
            }

            val b = when {
                isEncrypted -> testEncryptedBuilder
                isLegacy    -> testLegacyBuilder
                else        -> testActualBuilder
            }

            // One addStatement per call so KotlinPoet emits each as a sibling
            // statement node (not continuation-indented under the first).
            val fs = FunSpec.builder(name)
                .addAnnotation(ClassName("org.junit", "Test"))
                .addModifiers(KModifier.PUBLIC)
            lines.forEach { fs.addStatement(it) }

            if (!isEncrypted && isLegacy) {
                val l = comments[constructor.layerLast - 1][constructor.tl.key.name.type] ?: emptyList()
                val comment = l.map { it.joinToString(separator = "-") }.joinToString(separator = "\n")
                fs.addKdoc(comment)
            }

            b.addFunction(fs.build())
        }

        FileSpec.builder(packageName, "Test_All")
            .addImport("org.telegram.tgnet.model", "TlGen_Object", "TlGen_Vector")
            .addImport("org.junit.Assume", "assumeTrue")
            .addType(
                testAllBuilder
                    .addType(testActualBuilder.build())
                    .addType(testEncryptedBuilder.build())
                    .addType(testLegacyBuilder.build())
                    .build()
            )
            .build()
            .writeTo(outputDir)
    }

    // =========================================================
    // Линковка — бывшие private fun в main.kt
    // =========================================================

    private fun linkObjectsByUniqueId(
        classesByUniqueIds: Map<UInt, TelegramTlClass>,
        objectsByUniqueIds: Map<UInt, TlObjectWithLayer>,
        output: MutableSet<Pair<TlObjectWithLayer, TelegramTlClass>>
    ) {
        for (entry in objectsByUniqueIds) {
            val clazz = classesByUniqueIds[entry.value.tl.key.constructorId] ?: continue
            output.add(entry.value to clazz)
        }
    }

    private fun linkTypesBySinglePredicate(
        classesByUniqueIds: Map<UInt, TelegramTlClass>,
        predicatesByType: Map<String, List<TlObjectWithLayer>>,
        output: MutableSet<Pair<String, TelegramTlClass>>
    ) {
        for (constructor in predicatesByType.filterValues { it.size == 1 }.values.flatten()) {
            val clazz = classesByUniqueIds[constructor.tl.key.constructorId] ?: continue
            if (!clazz.canStaticDeserialize) continue
            output.add(constructor.tl.key.name.type to clazz)
        }
    }

    private fun linkTypesByLinkedObjects(
        classes: Set<TelegramTlClass>,
        linkedObjects: Set<Pair<TlObjectWithLayer, TelegramTlClass>>,
        output: MutableSet<Pair<String, TelegramTlClass>>
    ) {
        val linkedClassesMap = linkedObjects
            .map { it.second to it.first }
            .groupBy { it.first }
            .mapValues { it.value.map { x -> x.second } }

        classes.filter { it.canStaticDeserialize && it.staticDeserializeCreations.isNotEmpty() }
            .forEach { clazz ->
                val candidates = clazz.staticDeserializeCreations
                    .map { name -> classes.filter { it.name == name || it.fullName == name } }
                    .flatten().toSet()
                val linkedCandidates = candidates
                    .mapNotNull { linkedClassesMap[it] }.flatten()
                    .map { it.tl.key.name.type }
                val linkedCandidatesSet = linkedCandidates.toSet()

                when {
                    clazz.name in linkedCandidatesSet ->
                        output.add(clazz.name to clazz)
                    linkedCandidatesSet.size == 1 ->
                        output.add(linkedCandidatesSet.first() to clazz)
                    linkedCandidatesSet.isNotEmpty() -> {
                        println("Warning: multiple candidates for ${clazz.name}")
                        mostFrequentStringOrNull(linkedCandidates)?.let {
                            output.add(it to clazz)
                        }
                    }
                }
            }
    }

    private fun mostFrequentStringOrNull(input: List<String>): String? {
        val counts = input.groupingBy { it }.eachCount()
        val maxCount = counts.values.maxOrNull() ?: return null
        val top = counts.filterValues { it == maxCount }
        return if (top.size == 1) top.keys.first() else null
    }
}