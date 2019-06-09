package com.schibsted.spain.retroswagger.annotation

annotation class RetroswaggerHeader(
    val functionName: String,
    val headers: Array<String>
)