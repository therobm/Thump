package com.therobm.thump.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Raw `SQLiteOpenHelper` over ThumpData's metadata database. Hand-written SQL only — no ORM,
 * no Room, no schema diffing. Migrations are driven by `ThumpDatabaseMigrations.ALL_MIGRATIONS`
 * and applied in ascending `targetVersion` order; each applied version is recorded in the
 * `schema_versions` table so a re-run can pick up partial work.
 *
 * Opened in WAL journal mode (`PRAGMA journal_mode=WAL`) for safe multi-process access — the
 * UI Activity process and the MediaLibraryService process both open the same file.
 */
class ThumpDatabase(
    applicationContext: Context,
) : SQLiteOpenHelper(
    applicationContext,
    DATABASE_FILE_NAME,
    null,
    CURRENT_SCHEMA_VERSION,
) {

    override fun onConfigure(database: SQLiteDatabase) {
        super.onConfigure(database)
        // WAL has to be set per database connection. SQLiteOpenHelper opens a fresh one here.
        database.enableWriteAheadLogging()
    }

    override fun onCreate(database: SQLiteDatabase) {
        applyMigrationsUpTo(database, fromVersionExclusive = 0, toVersionInclusive = CURRENT_SCHEMA_VERSION)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        applyMigrationsUpTo(database, fromVersionExclusive = oldVersion, toVersionInclusive = newVersion)
    }

    private fun applyMigrationsUpTo(
        database: SQLiteDatabase,
        fromVersionExclusive: Int,
        toVersionInclusive: Int,
    ): Unit {
        val orderedMigrations: List<ThumpDatabaseMigration> = ThumpDatabaseMigrations.ALL_MIGRATIONS
            .sortedBy { migration: ThumpDatabaseMigration -> migration.targetVersion }
        val migrationCount: Int = orderedMigrations.size
        for (migrationIndex in 0 until migrationCount) {
            val migration: ThumpDatabaseMigration = orderedMigrations[migrationIndex]
            if (migration.targetVersion <= fromVersionExclusive) {
                continue
            }
            if (migration.targetVersion > toVersionInclusive) {
                continue
            }
            database.beginTransaction()
            try {
                val statementCount: Int = migration.statements.size
                for (statementIndex in 0 until statementCount) {
                    database.execSQL(migration.statements[statementIndex])
                }
                val appliedAt: Long = System.currentTimeMillis()
                database.execSQL(
                    "INSERT OR REPLACE INTO schema_versions (version, applied_at_epoch_millis) VALUES (?, ?)",
                    arrayOf<Any?>(migration.targetVersion, appliedAt),
                )
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    companion object {
        const val DATABASE_FILE_NAME: String = "thump_data.db"
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
