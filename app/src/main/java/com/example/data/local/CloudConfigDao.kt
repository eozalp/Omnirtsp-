package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.CloudConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudConfigDao {
    @Query("SELECT * FROM cloud_config WHERE id = 'primary_cloud_config' LIMIT 1")
    fun getCloudConfig(): Flow<CloudConfigEntity?>

    @Query("SELECT * FROM cloud_config WHERE id = 'primary_cloud_config' LIMIT 1")
    suspend fun getCloudConfigSync(): CloudConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateConfig(config: CloudConfigEntity)

    @Update
    suspend fun updateConfig(config: CloudConfigEntity)
}
