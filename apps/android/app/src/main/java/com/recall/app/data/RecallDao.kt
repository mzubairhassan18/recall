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

    @Query("SELECT COUNT(*) FROM captures WHERE archived = 0")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM captures WHERE archived = 1")
    suspend fun countArchived(): Int

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun find(id: String): CaptureEntity?

    @Query("SELECT * FROM captures WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<CaptureEntity>

    @Query("SELECT * FROM captures WHERE synced = 0 ORDER BY created_at ASC, id ASC LIMIT :limit")
    suspend fun unsynced(limit: Int): List<CaptureEntity>

    @Query("UPDATE captures SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT * FROM captures WHERE archived = 0 ORDER BY created_at DESC, id ASC LIMIT :limit")
    suspend fun recent(limit: Int): List<CaptureEntity>

    @Query(
        """
        SELECT DISTINCT c.* FROM captures c
        LEFT JOIN annotations a ON a.target_id = c.id AND a.kind = 'tag'
        WHERE c.archived = 0
          AND (:type IS NULL OR c.type = :type)
          AND (:domain IS NULL OR lower(coalesce(c.url, '')) LIKE '%' || lower(:domain) || '%')
          AND (:favorite = 0 OR c.favorite = 1)
          AND (:tag IS NULL OR lower(coalesce(a.value, '')) = lower(:tag))
        ORDER BY CASE WHEN :descending = 1 THEN c.created_at END DESC,
                 CASE WHEN :descending = 0 THEN c.created_at END ASC,
                 c.id ASC
        LIMIT :limit
        """,
    )
    suspend fun timeline(
        type: String?,
        domain: String?,
        favorite: Int,
        tag: String?,
        descending: Int,
        limit: Int,
    ): List<CaptureEntity>

    @Query("SELECT * FROM captures WHERE archived = 1 ORDER BY created_at DESC, id ASC LIMIT :limit")
    suspend fun archived(limit: Int): List<CaptureEntity>

    @Query("SELECT * FROM captures ORDER BY created_at ASC, id ASC")
    suspend fun all(): List<CaptureEntity>

    @Query("UPDATE captures SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Int)

    @Query("UPDATE captures SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Int)

    @Query("UPDATE captures SET archived = 1 WHERE id IN (:ids)")
    suspend fun archiveMany(ids: List<String>): Int

    @Query("UPDATE captures SET archived = 0 WHERE id IN (:ids)")
    suspend fun restoreMany(ids: List<String>): Int

    @Query(
        "SELECT * FROM annotations WHERE target_id = :id AND kind = 'tag' " +
            "ORDER BY created_at ASC, value COLLATE NOCASE",
    )
    suspend fun tagsFor(id: String): List<AnnotationEntity>

    @Query(
        "SELECT * FROM annotations WHERE target_id IN (:ids) AND kind = 'tag' " +
            "ORDER BY created_at ASC, value COLLATE NOCASE",
    )
    suspend fun tagsForIds(ids: List<String>): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE kind = 'tag' ORDER BY created_at ASC")
    suspend fun allTagAnnotations(): List<AnnotationEntity>

    @Query(
        "SELECT DISTINCT a.value FROM annotations a " +
            "JOIN captures c ON c.id = a.target_id " +
            "WHERE a.kind = 'tag' AND c.archived = 0 " +
            "AND a.value IS NOT NULL AND a.value != '' ORDER BY a.value COLLATE NOCASE",
    )
    suspend fun allTags(): List<String>

    @Query("DELETE FROM annotations WHERE target_id = :id AND kind = 'tag' AND value = :value")
    suspend fun deleteTag(id: String, value: String)
}
