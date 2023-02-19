/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.squareup.javapoet.ClassName
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.NameAllocator
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.TypeSpec
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Year
import java.util.Objects
import javax.lang.model.element.Modifier

// JavaPoet only supports line comments, and can't add a newline after file level comments.
val FILE_HEADER = """
    /*
     * Copyright (C) ${Year.now().value} The Android Open Source Project
     *
     * Licensed under the Apache License, Version 2.0 (the "License");
     * you may not use this file except in compliance with the License.
     * You may obtain a copy of the License at
     *
     *      http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software
     * distributed under the License is distributed on an "AS IS" BASIS,
     * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     * See the License for the specific language governing permissions and
     * limitations under the License.
     */

    // Generated by xmlpersistence. DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    // @formatter:off
""".trimIndent() + "\n\n"

private val atomicFileType = ClassName.get("android.util", "AtomicFile")

fun generate(persistence: PersistenceInfo): JavaFile {
    val distinctClassFields = persistence.root.allClassFields.distinctBy { it.type }
    val type = TypeSpec.classBuilder(persistence.name)
        .addJavadoc(
            """
                Generated class implementing XML persistence for${'$'}W{@link $1T}.
                <p>
                This class provides atomicity for persistence via {@link $2T}, however it does not provide
                thread safety, so please bring your own synchronization mechanism.
            """.trimIndent(), persistence.root.type, atomicFileType
        )
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .addField(generateFileField())
        .addMethod(generateConstructor())
        .addMethod(generateReadMethod(persistence.root))
        .addMethod(generateParseMethod(persistence.root))
        .addMethods(distinctClassFields.map { generateParseClassMethod(it) })
        .addMethod(generateWriteMethod(persistence.root))
        .addMethod(generateSerializeMethod(persistence.root))
        .addMethods(distinctClassFields.map { generateSerializeClassMethod(it) })
        .addMethod(generateDeleteMethod())
        .build()
    return JavaFile.builder(persistence.root.type.packageName(), type)
        .skipJavaLangImports(true)
        .indent("    ")
        .build()
}

private val nonNullType = ClassName.get("android.annotation", "NonNull")

private fun generateFileField(): FieldSpec =
    FieldSpec.builder(atomicFileType, "mFile", Modifier.PRIVATE, Modifier.FINAL)
        .addAnnotation(nonNullType)
        .build()

private fun generateConstructor(): MethodSpec =
    MethodSpec.constructorBuilder()
        .addJavadoc(
            """
                Create an instance of this class.

                @param file the XML file for persistence
            """.trimIndent()
        )
        .addModifiers(Modifier.PUBLIC)
        .addParameter(
            ParameterSpec.builder(File::class.java, "file").addAnnotation(nonNullType).build()
        )
        .addStatement("mFile = new \$1T(file)", atomicFileType)
        .build()

private val nullableType = ClassName.get("android.annotation", "Nullable")

private val xmlPullParserType = ClassName.get("org.xmlpull.v1", "XmlPullParser")

private val xmlType = ClassName.get("android.util", "Xml")

private val xmlPullParserExceptionType = ClassName.get("org.xmlpull.v1", "XmlPullParserException")

private fun generateReadMethod(rootField: ClassFieldInfo): MethodSpec =
    MethodSpec.methodBuilder("read")
        .addJavadoc(
            """
                Read${'$'}W{@link $1T}${'$'}Wfrom${'$'}Wthe${'$'}WXML${'$'}Wfile.

                @return the persisted${'$'}W{@link $1T},${'$'}Wor${'$'}W{@code null}${'$'}Wif${'$'}Wthe${'$'}WXML${'$'}Wfile${'$'}Wdoesn't${'$'}Wexist
                @throws IllegalArgumentException if an error occurred while reading
            """.trimIndent(), rootField.type
        )
        .addAnnotation(nullableType)
        .addModifiers(Modifier.PUBLIC)
        .returns(rootField.type)
        .addControlFlow("try (\$1T inputStream = mFile.openRead())", FileInputStream::class.java) {
            addStatement("final \$1T parser = \$2T.newPullParser()", xmlPullParserType, xmlType)
            addStatement("parser.setInput(inputStream, null)")
            addStatement("return parse(parser)")
            nextControlFlow("catch (\$1T e)", FileNotFoundException::class.java)
            addStatement("return null")
            nextControlFlow(
                "catch (\$1T | \$2T e)", IOException::class.java, xmlPullParserExceptionType
            )
            addStatement("throw new IllegalArgumentException(e)")
        }
        .build()

private val ClassFieldInfo.allClassFields: List<ClassFieldInfo>
    get() =
        mutableListOf<ClassFieldInfo>().apply {
            this += this@allClassFields
            for (field in fields) {
                when (field) {
                    is ClassFieldInfo -> this += field.allClassFields
                    is ListFieldInfo -> this += field.element.allClassFields
                }
            }
        }

private fun generateParseMethod(rootField: ClassFieldInfo): MethodSpec =
    MethodSpec.methodBuilder("parse")
        .addAnnotation(nonNullType)
        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
        .returns(rootField.type)
        .addParameter(
            ParameterSpec.builder(xmlPullParserType, "parser").addAnnotation(nonNullType).build()
        )
        .addExceptions(listOf(ClassName.get(IOException::class.java), xmlPullParserExceptionType))
        .apply {
            addStatement("int type")
            addStatement("int depth")
            addStatement("int innerDepth = parser.getDepth() + 1")
            addControlFlow(
                "while ((type = parser.next()) != \$1T.END_DOCUMENT\$W"
                    + "&& ((depth = parser.getDepth()) >= innerDepth || type != \$1T.END_TAG))",
                xmlPullParserType
            ) {
                addControlFlow(
                    "if (depth > innerDepth || type != \$1T.START_TAG)", xmlPullParserType
                ) {
                    addStatement("continue")
                }
                addControlFlow(
                    "if (\$1T.equals(parser.getName(),\$W\$2S))", Objects::class.java,
                    rootField.tagName
                ) {
                    addStatement("return \$1L(parser)", rootField.parseMethodName)
                }
            }
            addStatement(
                "throw new IllegalArgumentException(\$1S)",
                "Missing root tag <${rootField.tagName}>"
            )
        }
        .build()

private fun generateParseClassMethod(classField: ClassFieldInfo): MethodSpec =
    MethodSpec.methodBuilder(classField.parseMethodName)
        .addAnnotation(nonNullType)
        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
        .returns(classField.type)
        .addParameter(
            ParameterSpec.builder(xmlPullParserType, "parser").addAnnotation(nonNullType).build()
        )
        .apply {
            val (attributeFields, tagFields) = classField.fields
                .partition { it is PrimitiveFieldInfo || it is StringFieldInfo }
            if (tagFields.isNotEmpty()) {
                addExceptions(
                    listOf(ClassName.get(IOException::class.java), xmlPullParserExceptionType)
                )
            }
            val nameAllocator = NameAllocator().apply {
                newName("parser")
                newName("type")
                newName("depth")
                newName("innerDepth")
            }
            for (field in attributeFields) {
                val variableName = nameAllocator.newName(field.variableName, field)
                when (field) {
                    is PrimitiveFieldInfo -> {
                        val stringVariableName =
                            nameAllocator.newName("${field.variableName}String")
                        addStatement(
                            "final String \$1L =\$Wparser.getAttributeValue(null,\$W\$2S)",
                            stringVariableName, field.attributeName
                        )
                        if (field.isRequired) {
                            addControlFlow("if (\$1L == null)", stringVariableName) {
                                addStatement(
                                    "throw new IllegalArgumentException(\$1S)",
                                    "Missing attribute \"${field.attributeName}\""
                                )
                            }
                        }
                        val boxedType = field.type.box()
                        val parseTypeMethodName = if (field.type.isPrimitive) {
                            "parse${field.type.toString().capitalize()}"
                        } else {
                            "valueOf"
                        }
                        if (field.isRequired) {
                            addStatement(
                                "final \$1T \$2L =\$W\$3T.\$4L($5L)", field.type, variableName,
                                boxedType, parseTypeMethodName, stringVariableName
                            )
                        } else {
                            addStatement(
                                "final \$1T \$2L =\$W$3L != null ?\$W\$4T.\$5L($3L)\$W: null",
                                field.type, variableName, stringVariableName, boxedType,
                                parseTypeMethodName
                            )
                        }
                    }
                    is StringFieldInfo ->
                        addStatement(
                            "final String \$1L =\$Wparser.getAttributeValue(null,\$W\$2S)",
                            variableName, field.attributeName
                        )
                    else -> error(field)
                }
            }
            if (tagFields.isNotEmpty()) {
                for (field in tagFields) {
                    val variableName = nameAllocator.newName(field.variableName, field)
                    when (field) {
                        is ClassFieldInfo ->
                            addStatement("\$1T \$2L =\$Wnull", field.type, variableName)
                        is ListFieldInfo ->
                            addStatement(
                                "final \$1T \$2L =\$Wnew \$3T<>()", field.type, variableName,
                                ArrayList::class.java
                            )
                        else -> error(field)
                    }
                }
                addStatement("int type")
                addStatement("int depth")
                addStatement("int innerDepth = parser.getDepth() + 1")
                addControlFlow(
                    "while ((type = parser.next()) != \$1T.END_DOCUMENT\$W"
                        + "&& ((depth = parser.getDepth()) >= innerDepth || type != \$1T.END_TAG))",
                    xmlPullParserType
                ) {
                    addControlFlow(
                        "if (depth > innerDepth || type != \$1T.START_TAG)", xmlPullParserType
                    ) {
                        addStatement("continue")
                    }
                    addControlFlow("switch (parser.getName())") {
                        for (field in tagFields) {
                            addControlFlow("case \$1S:", field.tagName) {
                                val variableName = nameAllocator.get(field)
                                when (field) {
                                    is ClassFieldInfo -> {
                                        addControlFlow("if (\$1L != null)", variableName) {
                                            addStatement(
                                                "throw new IllegalArgumentException(\$1S)",
                                                "Duplicate tag \"${field.tagName}\""
                                            )
                                        }
                                        addStatement(
                                            "\$1L =\$W\$2L(parser)", variableName,
                                            field.parseMethodName
                                        )
                                        addStatement("break")
                                    }
                                    is ListFieldInfo -> {
                                        val elementNameAllocator = nameAllocator.clone()
                                        val elementVariableName = elementNameAllocator.newName(
                                            field.element.xmlName!!.toLowerCamelCase()
                                        )
                                        addStatement(
                                            "final \$1T \$2L =\$W\$3L(parser)", field.element.type,
                                            elementVariableName, field.element.parseMethodName
                                        )
                                        addStatement(
                                            "\$1L.add(\$2L)", variableName, elementVariableName
                                        )
                                        addStatement("break")
                                    }
                                    else -> error(field)
                                }
                            }
                        }
                    }
                }
            }
            for (field in tagFields.filter { it is ClassFieldInfo && it.isRequired }) {
                addControlFlow("if ($1L == null)", nameAllocator.get(field)) {
                    addStatement(
                        "throw new IllegalArgumentException(\$1S)", "Missing tag <${field.tagName}>"
                    )
                }
            }
            addStatement(
                classField.fields.joinToString(",\$W", "return new \$1T(", ")") {
                    nameAllocator.get(it)
                }, classField.type
            )
        }
        .build()

private val ClassFieldInfo.parseMethodName: String
    get() = "parse${type.simpleName().toUpperCamelCase()}"

private val xmlSerializerType = ClassName.get("org.xmlpull.v1", "XmlSerializer")

private fun generateWriteMethod(rootField: ClassFieldInfo): MethodSpec =
    MethodSpec.methodBuilder("write")
        .apply {
            val nameAllocator = NameAllocator().apply {
                newName("outputStream")
                newName("serializer")
            }
            val parameterName = nameAllocator.newName(rootField.variableName)
            addJavadoc(
                """
                    Write${'$'}W{@link $1T}${'$'}Wto${'$'}Wthe${'$'}WXML${'$'}Wfile.

                    @param $2L the${'$'}W{@link ${'$'}1T}${'$'}Wto${'$'}Wpersist
                """.trimIndent(), rootField.type, parameterName
            )
            addAnnotation(nullableType)
            addModifiers(Modifier.PUBLIC)
            addParameter(
                ParameterSpec.builder(rootField.type, parameterName)
                    .addAnnotation(nonNullType)
                    .build()
            )
            addStatement("\$1T outputStream = null", FileOutputStream::class.java)
            addControlFlow("try") {
                addStatement("outputStream = mFile.startWrite()")
                addStatement(
                    "final \$1T serializer =\$W\$2T.newSerializer()", xmlSerializerType, xmlType
                )
                addStatement(
                    "serializer.setOutput(outputStream, \$1T.UTF_8.name())",
                    StandardCharsets::class.java
                )
                addStatement(
                    "serializer.setFeature(\$1S, true)",
                    "http://xmlpull.org/v1/doc/features.html#indent-output"
                )
                addStatement("serializer.startDocument(null, true)")
                addStatement("serialize(serializer,\$W\$1L)", parameterName)
                addStatement("serializer.endDocument()")
                addStatement("mFile.finishWrite(outputStream)")
                nextControlFlow("catch (Exception e)")
                addStatement("e.printStackTrace()")
                addStatement("mFile.failWrite(outputStream)")
            }
        }
        .build()

private fun generateSerializeMethod(rootField: ClassFieldInfo): MethodSpec =
    MethodSpec.methodBuilder("serialize")
        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
        .addParameter(
            ParameterSpec.builder(xmlSerializerType, "serializer")
                .addAnnotation(nonNullType)
                .build()
        )
        .apply {
            val nameAllocator = NameAllocator().apply { newName("serializer") }
            val parameterName = nameAllocator.newName(rootField.variableName)
            addParameter(
                ParameterSpec.builder(rootField.type, parameterName)
                    .addAnnotation(nonNullType)
                    .build()
            )
            addException(IOException::class.java)
            addStatement("serializer.startTag(null, \$1S)", rootField.tagName)
            addStatement("\$1L(serializer, \$2L)", rootField.serializeMethodName, parameterName)
            addStatement("serializer.endTag(null, \$1S)", rootField.tagName)
        }
        .build()

private fun generateSerializeClassMethod(classField: ClassFieldInfo): MethodSpec =
    MethodSpec.methodBuilder(classField.serializeMethodName)
        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
        .addParameter(
            ParameterSpec.builder(xmlSerializerType, "serializer")
                .addAnnotation(nonNullType)
                .build()
        )
        .apply {
            val nameAllocator = NameAllocator().apply {
                newName("serializer")
                newName("i")
            }
            val parameterName = nameAllocator.newName(classField.serializeParameterName)
            addParameter(
                ParameterSpec.builder(classField.type, parameterName)
                    .addAnnotation(nonNullType)
                    .build()
            )
            addException(IOException::class.java)
            val (attributeFields, tagFields) = classField.fields
                .partition { it is PrimitiveFieldInfo || it is StringFieldInfo }
            for (field in attributeFields) {
                val variableName = "$parameterName.${field.name}"
                if (!field.isRequired) {
                    beginControlFlow("if (\$1L != null)", variableName)
                }
                when (field) {
                    is PrimitiveFieldInfo -> {
                        if (field.isRequired && !field.type.isPrimitive) {
                            addControlFlow("if (\$1L == null)", variableName) {
                                addStatement(
                                    "throw new IllegalArgumentException(\$1S)",
                                    "Field \"${field.name}\" is null"
                                )
                            }
                        }
                        val stringVariableName =
                            nameAllocator.newName("${field.variableName}String")
                        addStatement(
                            "final String \$1L =\$WString.valueOf(\$2L)", stringVariableName,
                            variableName
                        )
                        addStatement(
                            "serializer.attribute(null, \$1S, \$2L)", field.attributeName,
                            stringVariableName
                        )
                    }
                    is StringFieldInfo -> {
                        if (field.isRequired) {
                            addControlFlow("if (\$1L == null)", variableName) {
                                addStatement(
                                    "throw new IllegalArgumentException(\$1S)",
                                    "Field \"${field.name}\" is null"
                                )
                            }
                        }
                        addStatement(
                            "serializer.attribute(null, \$1S, \$2L)", field.attributeName,
                            variableName
                        )
                    }
                    else -> error(field)
                }
                if (!field.isRequired) {
                    endControlFlow()
                }
            }
            for (field in tagFields) {
                val variableName = "$parameterName.${field.name}"
                if (field.isRequired) {
                    addControlFlow("if (\$1L == null)", variableName) {
                        addStatement(
                            "throw new IllegalArgumentException(\$1S)",
                            "Field \"${field.name}\" is null"
                        )
                    }
                }
                when (field) {
                    is ClassFieldInfo -> {
                        addStatement("serializer.startTag(null, \$1S)", field.tagName)
                        addStatement(
                            "\$1L(serializer, \$2L)", field.serializeMethodName, variableName
                        )
                        addStatement("serializer.endTag(null, \$1S)", field.tagName)
                    }
                    is ListFieldInfo -> {
                        val sizeVariableName = nameAllocator.newName("${field.variableName}Size")
                        addStatement(
                            "final int \$1L =\$W\$2L.size()", sizeVariableName, variableName
                        )
                        addControlFlow("for (int i = 0;\$Wi < \$1L;\$Wi++)", sizeVariableName) {
                            val elementNameAllocator = nameAllocator.clone()
                            val elementVariableName = elementNameAllocator.newName(
                                field.element.xmlName!!.toLowerCamelCase()
                            )
                            addStatement(
                                "final \$1T \$2L =\$W\$3L.get(i)", field.element.type,
                                elementVariableName, variableName
                            )
                            addControlFlow("if (\$1L == null)", elementVariableName) {
                                addStatement(
                                    "throw new IllegalArgumentException(\$1S\$W+ i\$W+ \$2S)",
                                    "Field element \"${field.name}[", "]\" is null"
                                )
                            }
                            addStatement("serializer.startTag(null, \$1S)", field.element.tagName)
                            addStatement(
                                "\$1L(serializer,\$W\$2L)", field.element.serializeMethodName,
                                elementVariableName
                            )
                            addStatement("serializer.endTag(null, \$1S)", field.element.tagName)
                        }
                    }
                    else -> error(field)
                }
            }
        }
        .build()

private val ClassFieldInfo.serializeMethodName: String
    get() = "serialize${type.simpleName().toUpperCamelCase()}"

private val ClassFieldInfo.serializeParameterName: String
    get() = type.simpleName().toLowerCamelCase()

private val FieldInfo.variableName: String
    get() = name.toLowerCamelCase()

private val FieldInfo.attributeName: String
    get() {
        check(this is PrimitiveFieldInfo || this is StringFieldInfo)
        return xmlNameOrName.toLowerCamelCase()
    }

private val FieldInfo.tagName: String
    get() {
        check(this is ClassFieldInfo || this is ListFieldInfo)
        return xmlNameOrName.toLowerKebabCase()
    }

private val FieldInfo.xmlNameOrName: String
    get() = xmlName ?: name

private fun generateDeleteMethod(): MethodSpec =
    MethodSpec.methodBuilder("delete")
        .addJavadoc("Delete the XML file, if any.")
        .addModifiers(Modifier.PUBLIC)
        .addStatement("mFile.delete()")
        .build()

private inline fun MethodSpec.Builder.addControlFlow(
    controlFlow: String,
    vararg args: Any,
    block: MethodSpec.Builder.() -> Unit
): MethodSpec.Builder {
    beginControlFlow(controlFlow, *args)
    block()
    endControlFlow()
    return this
}