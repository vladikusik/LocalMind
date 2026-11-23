package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    @ColumnInfo(name = "model_id")
    val modelId: String = "",
    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversation_id", "timestamp"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "conversation_id")
    val conversationId: Long,
    val role: String, // "user" or "assistant"
    val content: String,
    @ColumnInfo(name = "thinking_content")
    val thinkingContent: String? = null,
    @ColumnInfo(name = "model_name")
    val modelName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_error")
    val isError: Boolean = false,
    @ColumnInfo(name = "tokens_per_second")
    val tokensPerSecond: Float? = null,
    @ColumnInfo(name = "attachments_json")
    val attachmentsJson: String? = null
)

@Entity(tableName = "downloaded_models")
data class DownloadedModelEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "repo_id")
    val repoId: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "local_path")
    val localPath: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    val format: String, // "task", "bin", "litertlm", "gguf"
    @ColumnInfo(name = "downloaded_at")
    val downloadedAt: Long = System.currentTimeMillis()
)

data class ConversationSummary(
    val id: Long,
    val title: String,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean,
    @ColumnInfo(name = "last_message")
    val lastMessage: String?,
    @ColumnInfo(name = "message_count")
    val messageCount: Int
)

data class StorageStats(
    @ColumnInfo(name = "chat_count")
    val chatCount: Int,
    @ColumnInfo(name = "message_count")
    val messageCount: Int
)
