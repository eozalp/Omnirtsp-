package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.CameraEntity
import com.example.data.model.CloudConfigEntity
import com.example.data.model.MotionAlertEntity
import com.example.data.model.RecordedClipEntity
import com.example.data.model.RecordingScheduleEntity

@Database(
    entities = [
        CameraEntity::class,
        MotionAlertEntity::class,
        RecordingScheduleEntity::class,
        RecordedClipEntity::class,
        CloudConfigEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cameraDao(): CameraDao
    abstract fun motionAlertDao(): MotionAlertDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun recordedClipDao(): RecordedClipDao
    abstract fun cloudConfigDao(): CloudConfigDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "omniview_rtsp.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
