package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedModelDao {

    @Query("SELECT * FROM downloaded_models ORDER BY downloaded_at DESC")
    fun observeDownloadedModels(): Flow<List<DownloadedModelEntity>>

    @Query("SELECT * FROM downloaded_models WHERE id = :id")
    suspend fun getById(id: String): DownloadedModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: DownloadedModelEntity)

    @Query("DELETE FROM downloaded_models WHERE id = :id")
    suspend fun deleteById(id: String)
}
