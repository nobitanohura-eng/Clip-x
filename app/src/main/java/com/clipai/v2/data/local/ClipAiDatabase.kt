package com.clipai.v2.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class,
        WordEntity::class,
        ClipEntity::class,
        ModelStateEntity::class,
        PipelineJobEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ClipAiDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun wordDao(): WordDao
    abstract fun clipDao(): ClipDao
    abstract fun modelStateDao(): ModelStateDao
    abstract fun pipelineJobDao(): PipelineJobDao

    companion object {
        @Volatile
        private var INSTANCE: ClipAiDatabase? = null

        fun getInstance(context: Context): ClipAiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClipAiDatabase::class.java,
                    "clipai_v2.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
