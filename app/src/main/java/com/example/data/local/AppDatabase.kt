package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.imagegen.GeneratedImageDao
import com.example.data.imagegen.GeneratedImageEntity
import com.example.data.mcp.AgentNoteEntity
import com.example.data.mcp.AgentProfileEntity
import com.example.data.mcp.McpAgentDao
import com.example.data.mcp.McpServerEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        DownloadedModelEntity::class,
        McpServerEntity::class,
        AgentProfileEntity::class,
        AgentNoteEntity::class,
        GeneratedImageEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun downloadedModelDao(): DownloadedModelDao
    abstract fun mcpAgentDao(): McpAgentDao
    abstract fun generatedImageDao(): GeneratedImageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `generated_images` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `negative_prompt` TEXT NOT NULL,
                        `local_path` TEXT NOT NULL,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `steps` INTEGER NOT NULL,
                        `cfg_scale` REAL NOT NULL,
                        `seed` INTEGER NOT NULL,
                        `engine_label` TEXT NOT NULL,
                        `model_name` TEXT NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val PERFORMANCE_CALLBACK = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                runCatching {
                    db.query("PRAGMA synchronous = NORMAL").close()
                    db.query("PRAGMA temp_store = MEMORY").close()
                    db.query("PRAGMA cache_size = -8000").close()
                }
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "localmind_chats.db"
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addCallback(PERFORMANCE_CALLBACK)
                    .addMigrations(MIGRATION_4_5)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
