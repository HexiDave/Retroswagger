package com.schibsted.spain.retroswagger.annotation

annotation class Retroswagger(
    val swaggerFilePath: String,
    val apiInterfaceName: String,
    val headers: Array<RetroswaggerHeader> = []
)