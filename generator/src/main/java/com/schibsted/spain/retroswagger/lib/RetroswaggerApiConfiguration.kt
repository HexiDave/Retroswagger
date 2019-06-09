package com.schibsted.spain.retroswagger.lib

data class RetroswaggerApiConfiguration(
    val packageName: String,
    val componentName: String,
    val moduleName: String,
    val swaggerFile: String,
    val headers: Map<String, Array<String>>
)