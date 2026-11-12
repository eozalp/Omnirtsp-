package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.CameraEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraDao {
    @Query("SELECT * FROM cameras ORDER BY name ASC")
    fun getAllCameras(): Flow<List<CameraEntity>>

    @Query("SELECT * FROM cameras WHERE id = :id LIMIT 1")
    suspend fun getCameraById(id: String): CameraEntity?

    @Query("SELECT * FROM cameras WHERE isEnabled = 1")
    suspend fun getEnabledCameras(): List<CameraEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCamera(camera: CameraEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCameras(cameras: List<CameraEntity>)

    @Update
    suspend fun updateCamera(camera: CameraEntity)

    @Delete
    suspend fun deleteCamera(camera: CameraEntity)

    @Query("UPDATE cameras SET rotationDegrees = :rotationDegrees WHERE id = :cameraId")
    suspend fun updateRotation(cameraId: String, rotationDegrees: Float)

    @Query("DELETE FROM cameras WHERE id = :id")
    suspend fun deleteCameraById(id: String)
}
