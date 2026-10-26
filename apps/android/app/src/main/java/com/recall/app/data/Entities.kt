package com.recall.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "captures",
    indices = [
        Index("created_at"),
        Index("type"),
        Index("synced"),
        Index("favorite"),
        Index("archived"),
    ],
)
data class CaptureEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val type: String,
    val url: String?,
    val title: String?,
    val text: String?,
    @ColumnInfo(name = "ocr_text") val ocrText: String?,
    val thumbnail: ByteArray?,
    @ColumnInfo(name = "blob_ref") val blobRef: String,
    val synced: Int = 0,
    val favorite: Int = 0,
    val archived: Int = 0,
)

@Entity(tableName = "annotations", indices = [Index("target_id")])
data class AnnotationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    val kind: String,
    val value: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
