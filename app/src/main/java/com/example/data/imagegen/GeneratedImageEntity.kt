package com.example.data.imagegen

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "generated_images")
data class GeneratedImageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val prompt: String,
    @ColumnInfo(name = "negative_prompt")
    val negativePrompt: String = "",
    @ColumnInfo(name = "local_path")
    val localPath: String,
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    @ColumnInfo(name = "cfg_scale")
    val cfgScale: Float = 7.0f,
    val seed: Long = -1L,
    @ColumnInfo(name = "engine_label")
    val engineLabel: String = "",
    @ColumnInfo(name = "model_name")
    val modelName: String = "",
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface GeneratedImageDao {
    @Query("SELECT * FROM generated_images ORDER BY created_at DESC")
    fun observeImages(): Flow<List<GeneratedImageEntity>>

    @Query("SELECT * FROM generated_images ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatest(): GeneratedImageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: GeneratedImageEntity): Long

    @Query("DELETE FROM generated_images WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM generated_images")
    suspend fun deleteAll()
}
