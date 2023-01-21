package com.kylecorry.trail_sense.tools.maps.infrastructure

import androidx.room.*
import com.kylecorry.trail_sense.tools.maps.domain.MapEntity

@Dao
interface MapDao {
    @Query("SELECT filename FROM maps")
    suspend fun getAllFilenames(): List<String>

    @Query("SELECT * FROM maps where parent IS :parent")
    suspend fun getAllWithParent(parent: Long?): List<MapEntity>

    @Query("SELECT * FROM maps WHERE _id = :id LIMIT 1")
    suspend fun get(id: Long): MapEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(map: MapEntity): Long

    @Delete
    suspend fun delete(map: MapEntity)

    @Query("DELETE FROM maps WHERE parent is :parent")
    suspend fun deleteInGroup(parent: Long?)

    @Update
    suspend fun update(map: MapEntity)
}