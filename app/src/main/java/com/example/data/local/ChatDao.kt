package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query(
        """
        SELECT c.id, c.title, c.model_id, c.updated_at, c.is_pinned,
               (SELECT m.content FROM messages m WHERE m.conversation_id = c.id ORDER BY m.timestamp DESC, m.id DESC LIMIT 1) AS last_message,
               (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) AS message_count
        FROM conversations c
        WHERE (:query = '' OR c.title LIKE '%' || :query || '%' OR EXISTS (
            SELECT 1 FROM messages m WHERE m.conversation_id = c.id AND m.content LIKE '%' || :query || '%'
        ))
        ORDER BY c.is_pinned DESC, c.updated_at DESC
        """
    )
    fun observeConversations(query: String): Flow<List<ConversationSummary>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversationById(conversationId: Long): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC, id ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC, id ASC")
    suspend fun getMessagesSnapshot(conversationId: Long): List<MessageEntity>

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM conversations) AS chat_count,
            (SELECT COUNT(*) FROM messages) AS message_count
        """
    )
    fun observeStorageStats(): Flow<StorageStats>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query(
        """
        UPDATE messages
        SET content = :content,
            thinking_content = :thinkingContent,
            model_name = :modelName,
            is_error = :isError,
            tokens_per_second = :tokensPerSecond
        WHERE id = :messageId
        """
    )
    suspend fun updateMessageContent(
        messageId: Long,
        content: String,
        thinkingContent: String?,
        modelName: String?,
        isError: Boolean,
        tokensPerSecond: Float?
    )

    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :conversationId")
    suspend fun renameConversation(conversationId: Long, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET updated_at = :updatedAt, model_id = :modelId WHERE id = :conversationId")
    suspend fun touchConversation(conversationId: Long, updatedAt: Long, modelId: String)

    @Query("UPDATE conversations SET is_pinned = :isPinned WHERE id = :conversationId")
    suspend fun setPinned(conversationId: Long, isPinned: Boolean)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversationById(conversationId: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()
}
