package com.recall.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RecallDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCapture(capture: CaptureEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAnnotation(annotation: AnnotationEntity): Long

    @Query("SELECT COUNT(*) FROM captures")
    suspend fun count(): Int

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun find(id: String): CaptureEntity?

    @Query("SELECT * FROM captures WHERE synced = 0 ORDER BY created_at ASC, id ASC LIMIT :limit")
    suspend fun unsynced(limit: Int): List<CaptureEntity>

    @Query("UPDATE captures SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT * FROM captures ORDER BY created_at DESC, id ASC LIMIT :limit")
    suspend fun recent(limit: Int): List<CaptureEntity>

    @Query(
        """
        SELECT * FROM captures
        WHERE (:type IS NULL OR type = :type)
          AND (:domain IS NULL OR lower(coalesce(url, '')) LIKE '%' || lower(:domain) || '%')
        ORDER BY created_at DESC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun timeline(type: String?, domain: String?, limit: Int): List<CaptureEntity>

    @Query("SELECT * FROM captures ORDER BY created_at ASC, id ASC")
    suspend fun all(): List<CaptureEntity>
}
