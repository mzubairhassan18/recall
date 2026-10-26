package com.recall.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * SQLCipher-backed Room database. SQLCipher encrypts the entire file, including the FTS5 index,
 * with a key derived from the user's passphrase (SQLCipher 4 PBKDF2-HMAC-SHA512).
 *
 * The `captures_fts` FTS5 table is created outside Room (Room only understands FTS4) and kept in
 * sync with triggers, exactly like the TypeScript apps. SQLCipher's bundled SQLite is compiled
 * with ENABLE_FTS5 (verified against the 4.6.1 AAR), so `bm25()` behaves like the other clients.
 */
@Database(
    entities = [CaptureEntity::class, AnnotationEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class RecallDatabase : RoomDatabase() {
    abstract fun dao(): RecallDao

    companion object {
        private const val NAME = "recall.db"

        @Volatile
        private var nativeLoaded = false

        @Synchronized
        private fun loadNative() {
            if (nativeLoaded) return
            System.loadLibrary("sqlcipher")
            nativeLoaded = true
        }

        fun open(context: Context, passphrase: CharArray): RecallDatabase {
            loadNative()
            val bytes = passphrase.concatToString().toByteArray(Charsets.UTF_8)
            val factory = SupportOpenHelperFactory(bytes, null, true)
            return Room.databaseBuilder(context.applicationContext, RecallDatabase::class.java, NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2)
                .addCallback(FtsCallback)
                .build()
        }
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE captures ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE captures ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_captures_favorite ON captures(favorite)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_captures_archived ON captures(archived)")
    }
}

private object FtsCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        createFts(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        createFts(db)
        val captures = db.query("SELECT COUNT(*) FROM captures").use { it.moveToFirst(); it.getInt(0) }
        val indexed = db.query("SELECT COUNT(*) FROM captures_fts").use { it.moveToFirst(); it.getInt(0) }
        if (captures != indexed) {
            db.execSQL("INSERT INTO captures_fts(captures_fts) VALUES('rebuild')")
        }
    }

    private fun createFts(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS captures_fts USING fts5(
              title, text, ocr_text, url,
              content='captures', content_rowid='rowid'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS captures_ai AFTER INSERT ON captures BEGIN
              INSERT INTO captures_fts(rowid, title, text, ocr_text, url)
              VALUES (new.rowid, new.title, new.text, new.ocr_text, new.url);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS captures_ad AFTER DELETE ON captures BEGIN
              INSERT INTO captures_fts(captures_fts, rowid, title, text, ocr_text, url)
              VALUES ('delete', old.rowid, old.title, old.text, old.ocr_text, old.url);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS captures_au AFTER UPDATE ON captures BEGIN
              INSERT INTO captures_fts(captures_fts, rowid, title, text, ocr_text, url)
              VALUES ('delete', old.rowid, old.title, old.text, old.ocr_text, old.url);
              INSERT INTO captures_fts(rowid, title, text, ocr_text, url)
              VALUES (new.rowid, new.title, new.text, new.ocr_text, new.url);
            END
            """.trimIndent(),
        )
    }
}
