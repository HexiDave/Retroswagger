package com.schibsted.spain.retroswagger.lib

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.moshi.JsonClass
import io.swagger.models.*
import io.swagger.models.parameters.BodyParameter
import io.swagger.models.parameters.Parameter
import io.swagger.models.parameters.PathParameter
import io.swagger.models.parameters.QueryParameter
import io.swagger.models.properties.*
import io.swagger.parser.SwaggerParser
import kotlinx.coroutines.Deferred
import retrofit2.http.*
import retrofit2.http.Path
import java.io.FileNotFoundException
import java.net.UnknownHostException
import kotlin.reflect.KClass

class RetroswaggerApiBuilder(
    private val retroswaggerApiConfiguration: RetroswaggerApiConfiguration,
    private val errorTracking: RetroswaggerErrorTracking
) {
    companion object {
        const val OK_RESPONSE = "200"
        const val ARRAY_SWAGGER_TYPE = "array"
        const val INTEGER_SWAGGER_TYPE = "integer"
        const val NUMBER_SWAGGER_TYPE = "number"
        const val STRING_SWAGGER_TYPE = "string"
        const val BOOLEAN_SWAGGER_TYPE = "boolean"
        const val REF_SWAGGER_TYPE = "ref"
    }

    private val swaggerModel: Swagger = try {
        SwaggerParser().read(retroswaggerApiConfiguration.swaggerFile)
    } catch (unknown: UnknownHostException) {
        errorTracking.logException(unknown)
        Swagger()
    } catch (illegal: IllegalStateException) {
        errorTracking.logException(illegal)
        Swagger()
    } catch (notFound: FileNotFoundException) {
        errorTracking.logException(notFound)
        Swagger()
    }

    private lateinit var apiInterfaceTypeSpec: TypeSpec
    private val responseBodyModelListTypeSpec: ArrayList<TypeSpec> = ArrayList()
    private val enumListTypeSpec: ArrayList<TypeSpec> = ArrayList()

    fun build() {
        createEnumClasses()
        apiInterfaceTypeSpec = createApiRetrofitInterface(createApiResponseBodyModel())
    }

    fun getGeneratedApiInterfaceTypeSpec(): TypeSpec {
        return apiInterfaceTypeSpec
    }

    fun getGeneratedModelListTypeSpec(): List<TypeSpec> {
        return responseBodyModelListTypeSpec
    }

    fun getGeneratedEnumListTypeSpec(): List<TypeSpec> {
        return enumListTypeSpec
    }

    private fun createEnumClasses() {
        addOperationResponseEnums()
        addModelEnums()
    }

    private fun createEnumClass(modelClass: KClass<*>, className: String, enumList: List<Pair<String, *>>): TypeSpec {
        val companionBuilder = TypeSpec.companionObjectBuilder()

        enumList.forEach {
            when (modelClass) {
                String::class -> PropertySpec.builder(it.first, String::class).initializer("%S", it.second).build()
                Int::class -> PropertySpec.builder(it.first, Int::class).initializer("%L", it.second).build()
                else -> null
            }?.run { companionBuilder.addProperty(this) }
        }

        return TypeSpec.classBuilder(className)
            .addType(companionBuilder.build())
            .build()
    }

    private fun getModelClass(type: String) = when (type) {
        INTEGER_SWAGGER_TYPE -> Int::class
        else -> String::class
    }

    private fun parseEnumValues(type: String, enumValues: List<String>): List<*> = when (type) {
        INTEGER_SWAGGER_TYPE -> {
            enumValues.map { it.toInt() }
        }

        else -> enumValues
    }

    private fun addModelEnums() {
        if (swaggerModel.definitions != null && !swaggerModel.definitions.isEmpty()) {
            for (definition in swaggerModel.definitions) {
                if (definition.value != null && definition.value.properties != null) {
                    for (modelProperty in definition.value.properties) {
                        if (modelProperty.value is StringProperty) {
                            val enumDefinition = (modelProperty.value as StringProperty).enum
                            if (enumDefinition != null) {
                                val enumTypeSpecBuilder = TypeSpec.enumBuilder(modelProperty.key.capitalize())
                                for (constant in enumDefinition) {
                                    enumTypeSpecBuilder.addEnumConstant(constant)
                                }
                                if (!enumListTypeSpec.contains(enumTypeSpecBuilder.build())) {
                                    enumListTypeSpec.add(enumTypeSpecBuilder.build())
                                }
                            }
                        } else if (modelProperty.value is RefProperty) {
                            try {
                                val simpleRef = (modelProperty.value as RefProperty).simpleRef

                                // If it's already in, move on
                                if (enumListTypeSpec.any { it.name == simpleRef })
                                    continue

                                val modelDefinition = swaggerModel.definitions[simpleRef] as? ModelImpl

                                if (modelDefinition != null && modelDefinition.enum != null) {
                                    val modelClass = getModelClass(modelDefinition.type)

                                    val enumClass = if (modelDefinition.vendorExtensions != null) {
                                        @Suppress("UNCHECKED_CAST")
                                        val names = modelDefinition.vendorExtensions["x-enumNames"] as ArrayList<String>
                                        val zipped =
                                            names.zip(parseEnumValues(modelDefinition.type, modelDefinition.enum))

                                        createEnumClass(modelClass, simpleRef, zipped)
                                    } else {
                                        null
                                    }

                                    enumClass?.let { enumListTypeSpec.add(it) }
                                }
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    }
                }
            }
        }
    }

    private fun addOperationResponseEnums() {
        if (swaggerModel.paths != null && !swaggerModel.paths.isEmpty()) {
            for (path in swaggerModel.paths) {
                for (operation in path.value.operationMap) {
                    try {
                        for (parameters in operation.value.parameters) {
                            if (parameters is PathParameter) {
                                if (parameters.enum != null) {
                                    val enumTypeSpecBuilder = TypeSpec.enumBuilder(parameters.name.capitalize())
                                    for (constant in parameters.enum) {
                                        enumTypeSpecBuilder.addEnumConstant(constant)
                                    }
                                    if (!enumListTypeSpec.contains(enumTypeSpecBuilder.build())) {
                                        enumListTypeSpec.add(enumTypeSpecBuilder.build())
                                    }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        errorTracking.logException(error)
                    }
                }
            }
        }
    }

    private fun createApiResponseBodyModel(): List<String> {
        val classNameList = ArrayList<String>()

        if (swaggerModel.definitions != null && !swaggerModel.definitions.isEmpty()) {
            for (definition in swaggerModel.definitions) {

                var modelClassTypeSpec: TypeSpec.Builder
                try {
                    modelClassTypeSpec = TypeSpec.classBuilder(definition.key).addModifiers(KModifier.DATA)
                    classNameList.add(definition.key)
                } catch (error: IllegalArgumentException) {
                    modelClassTypeSpec = TypeSpec.classBuilder("Model" + definition.key.capitalize())
                        .addModifiers(KModifier.DATA)
                    classNameList.add("Model" + definition.key.capitalize())
                }

                modelClassTypeSpec.addAnnotation(
                    AnnotationSpec.builder(JsonClass::class)
                        .addMember("generateAdapter = %L", true)
                        .build()
                )

                val primaryConstructor = FunSpec.constructorBuilder()

                val model = definition.value

                if (model is ComposedModel) {
                    model.allOf.forEach { addModelProperties(it, primaryConstructor, modelClassTypeSpec) }
                } else {
                    addModelProperties(model, primaryConstructor, modelClassTypeSpec)
                }

                modelClassTypeSpec.primaryConstructor(primaryConstructor.build())

                responseBodyModelListTypeSpec.add(modelClassTypeSpec.build())
            }
        }

        return classNameList
    }

    private fun addModelProperties(
        model: Model,
        primaryConstructor: FunSpec.Builder,
        modelClassTypeSpec: TypeSpec.Builder
    ) {
        if (model.properties != null) {
            for (modelProperty in model.properties) {
                val typeName: TypeName = getTypeName(modelProperty, swaggerModel.definitions)
                val propertySpec = PropertySpec.builder(modelProperty.key, typeName)
                    .initializer(modelProperty.key)
                    .build()
                primaryConstructor.addParameter(modelProperty.key, typeName)
                modelClassTypeSpec.addProperty(propertySpec)
            }
        }
    }

    private fun createApiRetrofitInterface(classNameList: List<String>): TypeSpec {
        val apiInterfaceTypeSpecBuilder = TypeSpec
            .interfaceBuilder("${retroswaggerApiConfiguration.componentName}ApiInterface")
            .addModifiers(KModifier.PUBLIC)

        addApiPathMethods(apiInterfaceTypeSpecBuilder, classNameList)

        return apiInterfaceTypeSpecBuilder.build()
    }

    private fun addApiPathMethods(apiInterfaceTypeSpec: TypeSpec.Builder, classNameList: List<String>) {
        if (swaggerModel.paths != null && !swaggerModel.paths.isEmpty()) {
            for (path in swaggerModel.paths) {
                for (operation in path.value.operationMap) {

                    val annotationSpec = when {
                        operation.key.name.contains(
                            "GET"
                        ) -> AnnotationSpec.builder(GET::class)
                            .addMember("\"${path.key.removePrefix("/")}\"").build()
                        operation.key.name.contains(
                            "POST"
                        ) -> AnnotationSpec.builder(POST::class)
                            .addMember("\"${path.key.removePrefix("/")}\"").build()
                        operation.key.name.contains(
                            "PUT"
                        ) -> AnnotationSpec.builder(PUT::class)
                            .addMember("\"${path.key.removePrefix("/")}\"").build()
                        operation.key.name.contains(
                            "PATCH"
                        ) -> AnnotationSpec.builder(PATCH::class)
                            .addMember("\"${path.key.removePrefix("/")}\"").build()
                        operation.key.name.contains(
                            "DELETE"
                        ) -> AnnotationSpec.builder(DELETE::class)
                            .addMember("\"${path.key.removePrefix("/")}\"").build()
                        else -> AnnotationSpec.builder(GET::class)
                            .addMember("\"${path.key.removePrefix("/")}\"").build()
                    }

                    try {
                        val returnedClass = getReturnedClass(operation, classNameList)
                        val methodParameters = getMethodParameters(operation)
                        val operationName = operation.value.operationId

                        val funSpecBuilder = FunSpec.builder(operationName)
                            .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT)
                            .addAnnotation(annotationSpec)
                            .addParameters(methodParameters)
                            .returns(returnedClass)

                        val header = retroswaggerApiConfiguration.headers[operationName]

                        header?.also {
                            val headerAnnotationSpec = AnnotationSpec.builder(Headers::class)
                                .also { builder ->
                                    it.forEach { h -> builder.addMember("%S", h) }
                                }.build()

                            funSpecBuilder.addAnnotation(headerAnnotationSpec)
                        }

                        apiInterfaceTypeSpec.addFunction(funSpecBuilder.build())
                    } catch (exception: Exception) {
                        errorTracking.logException(exception)
                    }
                }
            }
        }
    }

    private fun getTypeName(
        modelProperty: MutableMap.MutableEntry<String, Property>,
        definitions: MutableMap<String, Model>
    ): TypeName {
        val property = modelProperty.value
        return when {
            property.type == REF_SWAGGER_TYPE -> {
                val simpleRef = (property as RefProperty).simpleRef

                if (enumListTypeSpec.any { it.name == simpleRef }) {

                    val refModel = definitions[simpleRef] as ModelImpl

                    getKotlinClassTypeName(refModel.type, refModel.format).requiredOrNullable(property.required)
                } else {
                    TypeVariableName.invoke(simpleRef).requiredOrNullable(property.required)
                }
            }

            property.type == ARRAY_SWAGGER_TYPE -> {
                val arrayProperty = property as ArrayProperty
                getTypedArray(arrayProperty.items).requiredOrNullable(arrayProperty.required)
            }
            else -> getKotlinClassTypeName(property.type, property.format).requiredOrNullable(property.required)
        }
    }

    private fun getMethodParameters(
        operation: MutableMap.MutableEntry<HttpMethod, Operation>
    ): Iterable<ParameterSpec> {
        return operation.value.parameters.mapNotNull { parameter ->
            // Transform parameters in the format foo.bar to fooBar
            val name = parameter.name.split('.').mapIndexed { index, s -> if (index > 0) s.capitalize() else s }
                .joinToString("")
            when (parameter.`in`) {
                "body" -> {
                    ParameterSpec.builder(name, getBodyParameterSpec(parameter))
                        .addAnnotation(AnnotationSpec.builder(Body::class).build()).build()
                }
                "path" -> {
                    val type =
                        getKotlinClassTypeName((parameter as PathParameter).type, parameter.format).requiredOrNullable(
                            parameter.required
                        )
                    ParameterSpec.builder(name, type)
                        .addAnnotation(AnnotationSpec.builder(Path::class).addMember("\"${parameter.name}\"").build())
                        .build()
                }
                "query" -> {
                    if ((parameter as QueryParameter).type == ARRAY_SWAGGER_TYPE) {
                        val type =
                            List::class.asClassName().parameterizedBy(getKotlinClassTypeName(parameter.items.type))
                                .requiredOrNullable(parameter.required)
                        ParameterSpec.builder(name, type)
                    } else {
                        val type = getKotlinClassTypeName(
                            parameter.type,
                            parameter.format
                        ).requiredOrNullable(parameter.required)
                        ParameterSpec.builder(name, type)
                    }.addAnnotation(AnnotationSpec.builder(Query::class).addMember("\"${parameter.name}\"").build())
                        .build()
                }
                else -> null
            }
        }
    }

    private fun getBodyParameterSpec(parameter: Parameter): TypeName {
        val bodyParameter = parameter as BodyParameter
        val schema = bodyParameter.schema

        return when (schema) {
            is RefModel -> ClassName.bestGuess(schema.simpleRef.capitalize()).requiredOrNullable(parameter.required)

            is ArrayModel -> getTypedArray(schema.items).requiredOrNullable(parameter.required)

            else -> {
                val bodyParameter1 = parameter.schema as? ModelImpl ?: ModelImpl()

                when (bodyParameter1.type) {
                    STRING_SWAGGER_TYPE -> String::class.asClassName().requiredOrNullable(parameter.required)
                    BOOLEAN_SWAGGER_TYPE -> Boolean::class.asClassName().requiredOrNullable(parameter.required)
                    else -> ClassName.bestGuess(parameter.name.capitalize()).requiredOrNullable(parameter.required)
                }
            }
        }
    }

    private fun getTypedArray(items: Property): TypeName {
        val typeProperty = when (items) {
            is LongProperty -> TypeVariableName.invoke(Long::class.simpleName!!)
            is IntegerProperty -> TypeVariableName.invoke(Int::class.simpleName!!)
            is FloatProperty -> TypeVariableName.invoke(Float::class.simpleName!!)
            is DoubleProperty -> TypeVariableName.invoke(Double::class.simpleName!!)
            is RefProperty -> TypeVariableName.invoke(items.simpleRef)
            else -> getKotlinClassTypeName(items.type, items.format)
        }
        return List::class.asClassName().parameterizedBy(typeProperty)
    }

    private fun TypeName.requiredOrNullable(required: Boolean) = if (required) this else asNullable()

    private fun getReturnedClass(
        operation: MutableMap.MutableEntry<HttpMethod, Operation>,
        classNameList: List<String>
    ): TypeName {
        try {
            if (operation.value.responses[OK_RESPONSE]?.schema != null &&
                operation.value.responses[OK_RESPONSE]?.schema is RefProperty
            ) {
                val refProperty = (operation.value.responses[OK_RESPONSE]?.schema as RefProperty)
                var responseClassName = refProperty.simpleRef
                responseClassName = getValidClassName(responseClassName, refProperty)

                if (classNameList.contains(responseClassName)) {
                    return Deferred::class.asClassName().parameterizedBy(TypeVariableName.invoke(responseClassName))
                }
            } else if (operation.value.responses[OK_RESPONSE]?.schema != null &&
                operation.value.responses[OK_RESPONSE]?.schema is ArrayProperty
            ) {
                val refProperty = (operation.value.responses[OK_RESPONSE]?.schema as ArrayProperty)
                var responseClassName = (refProperty.items as RefProperty).simpleRef
                responseClassName = getValidClassName(responseClassName, (refProperty.items as RefProperty))

                if (classNameList.contains(responseClassName)) {
                    return Deferred::class.asClassName().parameterizedBy(
                        List::class.asClassName().parameterizedBy(TypeVariableName.invoke(responseClassName))
                    )
                }
            }
        } catch (error: ClassCastException) {
            errorTracking.logException(error)
        }

        return Deferred::class.asClassName().parameterizedBy(
            Unit::class.asClassName()
        )
    }

    private fun getValidClassName(responseClassName: String, refProperty: RefProperty): String {
        var className = responseClassName
        try {
            TypeSpec.classBuilder(className)
        } catch (error: IllegalArgumentException) {
            if (refProperty.simpleRef != null) {
                className = "Model" + refProperty.simpleRef.capitalize()
            }
        }
        return className
    }

    private fun getKotlinClassTypeName(type: String, format: String? = null): TypeName {
        return when (type) {
            ARRAY_SWAGGER_TYPE -> TypeVariableName.invoke(List::class.simpleName!!)
            STRING_SWAGGER_TYPE -> TypeVariableName.invoke(String::class.simpleName!!)
            NUMBER_SWAGGER_TYPE -> TypeVariableName.invoke(Double::class.simpleName!!)
            INTEGER_SWAGGER_TYPE -> {
                when (format) {
                    "int64" -> TypeVariableName.invoke(Long::class.simpleName!!)
                    else -> TypeVariableName.invoke(Int::class.simpleName!!)
                }
            }
            else -> TypeVariableName.invoke(type.capitalize())
        }
    }
}
