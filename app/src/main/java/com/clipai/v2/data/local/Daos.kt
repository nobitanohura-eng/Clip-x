package com.clipai.v2.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAtMs DESC")
    fun observeAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :projectId LIMIT 1")
    suspend fun getById(projectId: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteById(projectId: String)
}

@Dao
interface WordDao {
    @Query("SELECT * FROM words WHERE projectId = :projectId ORDER BY startSec ASC")
    fun observeWordsForProject(projectId: String): Flow<List<WordEntity>>

    @Query("SELECT * FROM words WHERE projectId = :projectId ORDER BY startSec ASC")
    suspend fun getWordsForProject(projectId: String): List<WordEntity>

    @Query("SELECT * FROM words WHERE fileHashSha256 = :fileHash ORDER BY startSec ASC")
    suspend fun getCachedWordsByHash(fileHash: String): List<WordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(words: List<WordEntity>)

    @Query("DELETE FROM words WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)

    @Query("UPDATE words SET text = :newText, confidence = 1.0, locked = 1 WHERE id = :wordId")
    suspend fun lockWordEdit(wordId: String, newText: String)
}

@Dao
interface ClipDao {
    @Query("SELECT * FROM clips WHERE projectId = :projectId ORDER BY selected DESC, totalScore DESC")
    fun observeClipsForProject(projectId: String): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE projectId = :projectId ORDER BY selected DESC, totalScore DESC")
    suspend fun getClipsForProject(projectId: String): List<ClipEntity>

    @Query("SELECT * FROM clips WHERE id = :clipId LIMIT 1")
    suspend fun getById(clipId: String): ClipEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(clips: List<ClipEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(clip: ClipEntity)

    @Query("DELETE FROM clips WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)

    @Query("UPDATE clips SET cropMode = :cropMode, manualCropOffsetX = :offsetX WHERE id = :clipId")
    suspend fun updateCropSettings(clipId: String, cropMode: String, offsetX: Float)

    @Query("UPDATE clips SET hookMode = :hookMode, verbatimHook = :verbatimHook, titleHook = :titleHook WHERE id = :clipId")
    suspend fun updateHookSettings(clipId: String, hookMode: String, verbatimHook: String, titleHook: String)

    @Query("UPDATE clips SET exportedFilePath = :path, qaPassed = :qaPassed WHERE id = :clipId")
    suspend fun updateExportResult(clipId: String, path: String, qaPassed: Boolean)
}

@Dao
interface ModelStateDao {
    @Query("SELECT * FROM model_states")
    fun observeAllModelStates(): Flow<List<ModelStateEntity>>

    @Query("SELECT * FROM model_states")
    suspend fun getAllModelStates(): List<ModelStateEntity>

    @Query("SELECT * FROM model_states WHERE modelId = :modelId LIMIT 1")
    suspend fun getById(modelId: String): ModelStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ModelStateEntity)

    @Query("DELETE FROM model_states WHERE modelId = :modelId")
    suspend fun deleteById(modelId: String)
}

@Dao
interface PipelineJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: PipelineJobEntity)

    @Query("SELECT * FROM pipeline_jobs WHERE projectId = :projectId ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun getLatestForProject(projectId: String): PipelineJobEntity?
}
