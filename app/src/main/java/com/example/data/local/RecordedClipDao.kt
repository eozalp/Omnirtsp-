package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.CloudSyncStatus
import com.example.data.model.RecordedClipEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordedClipDao {
    @Query("SELECT * FROM recorded_clips ORDER BY timestamp DESC")
    fun getAllClips(): Flow<List<RecordedClipEntity>>

    @Query("SELECT * FROM recorded_clips WHERE cameraId = :cameraId ORDER BY timestamp DESC")
    fun getClipsForCamera(cameraId: String): Flow<List<RecordedClipEntity>>

    @Query("SELECT * FROM recorded_clips WHERE cloudSyncStatus = 'PENDING_UPLOAD'")
    suspend fun getClipsPendingCloudSync(): List<RecordedClipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClip(clip: RecordedClipEntity)

    @Update
    suspend fun updateClip(clip: RecordedClipEntity)

    @Query("UPDATE recorded_clips SET cloudSyncStatus = :status, cloudStorageUrl = :cloudUrl, cloudProvider = :provider WHERE id = :clipId")
    suspend fun updateCloudSyncStatus(
        clipId: String,
        status: CloudSyncStatus,
        cloudUrl: String?,
        provider: String?
    )

    @Query("UPDATE recorded_clips SET localExportUri = :localUri WHERE id = :clipId")
    suspend fun updateLocalExportUri(clipId: String, localUri: String?)

    @Query("SELECT * FROM recorded_clips ORDER BY timestamp DESC")
    suspend fun getAllClipsSync(): List<RecordedClipEntity>

    @Query("SELECT * FROM recorded_clips WHERE timestamp < :cutoffTimestamp ORDER BY timestamp ASC")
    suspend fun getClipsOlderThan(cutoffTimestamp: Long): List<RecordedClipEntity>

    @Query("DELETE FROM recorded_clips WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteClipsOlderThan(cutoffTimestamp: Long): Int

    @Delete
    suspend fun deleteClip(clip: RecordedClipEntity)

    @Query("DELETE FROM recorded_clips WHERE id = :id")
    suspend fun deleteClipById(id: String)
}
