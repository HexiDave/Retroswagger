package com.schibsted.spain.retroswagger

import com.google.auto.service.AutoService
import com.schibsted.spain.retroswagger.annotation.Retroswagger
import com.schibsted.spain.retroswagger.lib.RetroswaggerApiBuilder
import com.schibsted.spain.retroswagger.lib.RetroswaggerApiConfiguration
import com.schibsted.spain.retroswagger.lib.RetroswaggerErrorTracking
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

@AutoService(Processor::class)
class RetroswaggerGenerator : AbstractProcessor() {
    override fun getSupportedAnnotationTypes(): MutableSet<String> {
        return mutableSetOf(Retroswagger::class.java.name)
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latest()
    }

    override fun process(set: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        val startTime = System.currentTimeMillis()
        roundEnv.getElementsAnnotatedWith(Retroswagger::class.java)
            .forEach {
                val className = it.simpleName.toString()
                val pack = processingEnv.elementUtils.getPackageOf(it).toString()

                val annotation = processingEnv.typeUtils.asElement(it.asType()).getAnnotation(Retroswagger::class.java)

                val headers = annotation.headers.associate { h -> h.functionName to h.headers }

                val configuration = RetroswaggerApiConfiguration(
                    pack, annotation.apiInterfaceName, className, annotation.swaggerFilePath, headers
                )

                processingEnv.messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "*** Processing: $className")
                processKotlin(configuration)
            }
        val endTime = System.currentTimeMillis()
        logElapsedProcessingTime(endTime, startTime)
        return true
    }

    private fun logElapsedProcessingTime(endTime: Long, startTime: Long) {
        val elapsedTimeInMillis = endTime - startTime
        val elapsedTime = SimpleDateFormat("s.SSS", Locale.getDefault()).format(elapsedTimeInMillis)
        processingEnv.messager.printMessage(
            Diagnostic.Kind.MANDATORY_WARNING,
            "*** Processed in $elapsedTime seconds"
        )
    }

    private fun processKotlin(
        configuration: RetroswaggerApiConfiguration
    ) {
        val kotlinApiBuilder = RetroswaggerApiBuilder(
            configuration,
            DummyRetroswaggerErrorTracking()
        )
        kotlinApiBuilder.build()

        generateClass(configuration, kotlinApiBuilder.getGeneratedApiInterfaceTypeSpec())
        for (typeSpec in kotlinApiBuilder.getGeneratedModelListTypeSpec()) {
            generateClass(configuration, typeSpec)
        }
        for (typeSpec in kotlinApiBuilder.getGeneratedEnumListTypeSpec()) {
            generateClass(configuration, typeSpec)
        }
    }

    private fun generateClass(
        configuration: RetroswaggerApiConfiguration,
        generatedTypeSpec: TypeSpec
    ) {
        val fileName = "Generated_${configuration.moduleName}"
        val file =
            FileSpec.builder(configuration.packageName, generatedTypeSpec.name!!).addType(generatedTypeSpec).build()

        val kaptKotlinGeneratedDir = processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME]
        file.writeTo(File(kaptKotlinGeneratedDir, "$fileName.kt"))
    }

    companion object {
        const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"
    }

    class DummyRetroswaggerErrorTracking : RetroswaggerErrorTracking {
        override fun logException(throwable: Throwable) {
        }
    }
}