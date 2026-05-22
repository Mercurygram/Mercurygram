package org.telegram.tlrpc.telegram

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.SwitchStmt
import java.io.File

object TelegramCodeParser {
    fun parse(files: List<File>): TelegramTlClasses {
        return TelegramTlClasses(files.map { findAllClasses(it) }.flatten().toSet())
    }

    private fun findAllClasses(javaFile: File): List<TelegramTlClass> {
        val parser = JavaParser()
        val result = parser.parse(javaFile)

        if (!result.isSuccessful || !result.result.isPresent) {
            return emptyList()
        }

        val cu: CompilationUnit = result.result.get()
        val packageName = cu.packageDeclaration
            .map { it.nameAsString }
            .orElse(null)


        val matchingClassNames = mutableListOf<TelegramTlClass>()

        val allClassLike = cu.findAll(ClassOrInterfaceDeclaration::class.java)

        for (cls in allClassLike) {
            val hasStaticIntConstructor = cls.fields.any { field ->
                field.isStatic &&
                field.commonType.toString() == "int" &&
                field.variables.any { it.nameAsString == "constructor" }
            }

            var constructorValue: Int? = null;
            if (hasStaticIntConstructor) {
                cls.fields.forEach { field ->
                    if (field.isStatic && field.commonType.toString() == "int") {
                        field.variables.forEach { variable ->
                            if (variable.nameAsString == "constructor") {
                                variable.initializer.ifPresent { expr ->
                                    expr.asIntegerLiteralExpr()?.let {
                                        constructorValue = it.asInt()
                                    }
                                }

                            }
                        }
                    }
                }
            }

            val staticDeserializeMethods = cls.methods.filter { method ->
                (method.nameAsString == "TLdeserialize" || method.nameAsString == "fromConstructor") && method.isStatic
            }
            val staticDeserializeCreations = staticDeserializeMethods.map(::findNewExpressionsInMethod).flatten().toSet().toList()
            val (dispatchedLiterals, dispatchedNames) =
                extractDispatchedMagics(staticDeserializeMethods, cls)
            val hasDeserializeMethod = staticDeserializeMethods.find { method ->
                    method.parameters.size >= 3 &&
                    method.parameters[0].type.toString() == "InputSerializedData" &&
                    method.parameters[1].type.toString() == "int" &&
                    method.parameters[2].type.toString() == "boolean" } != null

            val hasSerializeToStream = cls.methods.any { method ->
                method.nameAsString == "serializeToStream" &&
                method.isPublic &&
                method.parameters.size == 1 &&
                method.parameters[0].type.toString() == "OutputSerializedData" &&
                method.type.toString() == "void"
            }

            val hasReadParams = cls.methods.any { method ->
                method.nameAsString == "readParams" &&
                method.isPublic &&
                method.parameters.size == 2 &&
                method.parameters[0].type.toString() == "InputSerializedData" &&
                method.parameters[1].type.toString() == "boolean" &&
                method.type.toString() == "void"
            }

            val hasDeserializeResponse = cls.methods.any { method ->
                method.nameAsString == "deserializeResponse" &&
                method.isPublic &&
                method.type.asString() == "TLObject" &&
                method.parameters.size == 3 &&
                method.parameters[0].type.toString() == "InputSerializedData" &&
                method.parameters[1].type.toString() == "int" &&
                method.parameters[2].type.toString() == "boolean"
            }

            if (hasStaticIntConstructor || hasDeserializeMethod || hasSerializeToStream || hasReadParams || hasDeserializeResponse) {
                matchingClassNames.add(TelegramTlClass(
                    constructor = constructorValue?.toUInt(),

                    packageName = packageName,
                    fullName = getQualifiedName(cls),
                    name = cls.nameAsString,

                    canSerialize = hasSerializeToStream,
                    canDeserialize = hasReadParams,
                    canReadResponse = hasDeserializeResponse,
                    canStaticDeserialize = hasDeserializeMethod,
                    staticDeserializeCreations = staticDeserializeCreations,
                    dispatchedMagicLiterals = dispatchedLiterals,
                    dispatchedMagicNames = dispatchedNames
                ))
            }
        }

        return matchingClassNames
    }

    private fun findNewExpressionsInMethod(method: MethodDeclaration): List<String> {
        return method.findAll(ObjectCreationExpr::class.java)
            .map { it.typeAsString }
    }

    /**
     * Extract dispatched constructor magics from `TLdeserialize`/`fromConstructor`.
     *
     * Two complementary sources:
     *  - `IntegerLiteralExpr` labels inside `SwitchEntry` (e.g. `case 0xdeadbeef:`).
     *    Restricted to switch-case labels because integer literals elsewhere in
     *    these methods (array sizes, etc.) are not magics.
     *  - `FieldAccessExpr` named `constructor` anywhere in the method body. Covers
     *    both `case X.constructor:` switch dispatch and leaf-class identity checks
     *    `X.constructor != constructor ? null : new X()`. Within static
     *    `TLdeserialize`/`fromConstructor`, references to `<class>.constructor`
     *    are always dispatch references — no other use of that field name exists
     *    in these methods.
     *
     * Names are stored as raw `scope.toString()` (no qualification here). Resolution
     * to a class happens in GenerateSchemeTask via a staged lookup that tries the
     * full name, the dispatcher's enclosing-scope-qualified form, and finally a
     * simple-name fallback — necessary because case scopes may be cross-package
     * qualified (e.g. `TL_legacy_message.TL_message_layer224.constructor`) or
     * sibling-relative (e.g. `TL_groupCallDiscarded.constructor` inside
     * `TLRPC.GroupCall`).
     */
    private fun extractDispatchedMagics(
        methods: List<MethodDeclaration>,
        enclosingCls: ClassOrInterfaceDeclaration
    ): Pair<Set<UInt>, Set<String>> {
        val literals = mutableSetOf<UInt>()
        val names = mutableSetOf<String>()

        for (method in methods) {
            for (sw in method.findAll(SwitchStmt::class.java)) {
                for (entry in sw.entries) {
                    for (label in entry.labels) {
                        if (label is IntegerLiteralExpr) {
                            runCatching { label.asInt().toUInt() }.getOrNull()?.let { literals += it }
                        }
                    }
                }
            }
            for (fae in method.findAll(FieldAccessExpr::class.java)) {
                if (fae.name.asString() != "constructor") continue
                names += fae.scope.toString()
            }
        }
        return literals to names
    }

    private fun getQualifiedName(cls: ClassOrInterfaceDeclaration): String {
        val parent = cls.parentNode
        return if (parent.isPresent && parent.get() is ClassOrInterfaceDeclaration) {
            getQualifiedName(parent.get() as ClassOrInterfaceDeclaration) + "." + cls.nameAsString
        } else {
            cls.nameAsString
        }
    }
}