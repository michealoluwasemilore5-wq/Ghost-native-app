package com.mgghost.assistant

data class GhostAction(
    val type: String,
    val value: String = "",
    val confirmation: Boolean = false
)
