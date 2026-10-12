/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database hosting CallVault's recordings catalog. Single process. A plain
 * process-wide singleton ([get]) since there is no DI container in the app.
 *
 * The catalog now also owns per-call SIM snapshots and the merge manifest. Neither can be rebuilt
 * from audio files, so every schema upgrade uses explicit migrations and exports its schema.
 * There is no destructive fallback: an unsupported upgrade must never silently discard metadata.
 */
@Database(entities = [RecordingEntry::class, MergePartEntry::class], version = 4, exportSchema = true)
abstract class RecordingDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao

    abstract fun mergePartDao(): MergePartDao

    companion object {

        /**
         * Adds the remembered length. Additive and nullable, so every existing row keeps its URIs,
         * sizes and timestamp and simply starts with no duration — which is exactly the state that
         * makes it get read once, and only once.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN durationSeconds INTEGER")
            }
        }

        /**
         * Adds the merge manifest.
         *
         * A migration rather than a drop, and the one place in this file where the destructive
         * fallback would be genuinely destructive: re-seeding rebuilds the catalog by walking the
         * SAF folders, and no folder walk can tell you where one call ended and the next began
         * inside a merged recording. Losing these rows would leave the audio intact and playable but
         * permanently un-splittable, because merging deletes the originals.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `merge_parts` (" +
                        "`mergedName` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`partName` TEXT NOT NULL, " +
                        "`frameStart` INTEGER NOT NULL, " +
                        "`frameCount` INTEGER NOT NULL, " +
                        "`startTimeUs` INTEGER NOT NULL, " +
                        "`durationUs` INTEGER NOT NULL, " +
                        "`originalLastModified` INTEGER NOT NULL, " +
                        "`encoderDelayUs` INTEGER NOT NULL, " +
                        "`encoderPaddingUs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`mergedName`, `position`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_merge_parts_partName` ON `merge_parts` (`partName`)")
            }
        }

        @Volatile
        private var INSTANCE: RecordingDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN simAccountKey TEXT")
                db.execSQL("ALTER TABLE recordings ADD COLUMN simSubscriptionId INTEGER")
                db.execSQL("ALTER TABLE recordings ADD COLUMN simSlotIndex INTEGER")
                db.execSQL("ALTER TABLE recordings ADD COLUMN simDisplayName TEXT")
                db.execSQL("ALTER TABLE recordings ADD COLUMN simCarrierName TEXT")
            }
        }

        fun get(context: Context): RecordingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecordingDatabase::class.java,
                    "recordings.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { INSTANCE = it }
            }
    }
}
