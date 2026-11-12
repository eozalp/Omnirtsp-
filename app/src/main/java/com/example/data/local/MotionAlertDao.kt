package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.MotionAlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MotionAlertDao {
    @Query("SELECT * FROM motion_alerts ORDER BY timestamp DESC")
    fun getAllAlerts(): Flow<List<MotionAlertEntity>>

    @Query("SELECT * FROM motion_alerts WHERE cameraId = :cameraId ORDER BY timestamp DESC")
    fun getAlertsForCamera(cameraId: String): Flow<List<MotionAlertEntity>>

    @Query("SELECT COUNT(*) FROM motion_alerts WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: MotionAlertEntity)

    @Update
    suspend fun updateAlert(alert: MotionAlertEntity)

    @Query("UPDATE motion_alerts SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: String)

    @Query("UPDATE motion_alerts SET isRead = 1")
    suspend fun markAllAsRead()

    @Delete
    suspend fun deleteAlert(alert: MotionAlertEntity)

    @Query("DELETE FROM motion_alerts WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteAlertsOlderThan(cutoffTimestamp: Long): Int

    @Query("DELETE FROM motion_alerts")
    suspend fun clearAllAlerts()
}
