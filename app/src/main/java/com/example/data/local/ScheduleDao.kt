package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.RecordingScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM recording_schedules ORDER BY startHour ASC, startMinute ASC")
    fun getAllSchedules(): Flow<List<RecordingScheduleEntity>>

    @Query("SELECT * FROM recording_schedules WHERE isEnabled = 1")
    suspend fun getActiveSchedules(): List<RecordingScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: RecordingScheduleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedules(schedules: List<RecordingScheduleEntity>)

    @Update
    suspend fun updateSchedule(schedule: RecordingScheduleEntity)

    @Query("UPDATE recording_schedules SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setScheduleEnabled(id: String, isEnabled: Boolean)

    @Delete
    suspend fun deleteSchedule(schedule: RecordingScheduleEntity)

    @Query("DELETE FROM recording_schedules WHERE id = :id")
    suspend fun deleteScheduleById(id: String)
}
