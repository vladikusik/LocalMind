package com.example.data.network

import com.example.data.attachments.MessageAttachment
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ModelsResponseDto(
    val data: List<ModelItemDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ModelItemDto(
    val id: String,
    @Json(name = "owned_by")
    val ownedBy: String? = null
)

data class ChatMessageDto(
    val role: String,
    val content: String,
    val attachments: List<MessageAttachment> = emptyList()
)

data class LmStudioHealth(
    val models: List<String>,
    val latencyMs: Long
)

sealed interface StreamEvent {
    data class Chunk(
        val contentDelta: String = "",
        val reasoningDelta: String = "",
        val modelName: String? = null
    ) : StreamEvent

    data class Completed(
        val tokensPerSecond: Float?,
        val modelName: String?,
        val ttftMs: Long? = null,
        val prefillTokensPerSec: Float? = null,
        val kvCacheReused: Boolean = false
    ) : StreamEvent
}
