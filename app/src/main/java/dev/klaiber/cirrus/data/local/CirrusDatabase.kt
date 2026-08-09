package dev.klaiber.cirrus.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.klaiber.cirrus.data.local.dao.ConversationDao
import dev.klaiber.cirrus.data.local.dao.MessageDao
import dev.klaiber.cirrus.data.local.dao.PresetDao
import dev.klaiber.cirrus.data.local.entity.AttachmentEntity
import dev.klaiber.cirrus.data.local.entity.ConversationEntity
import dev.klaiber.cirrus.data.local.entity.MessageEntity
import dev.klaiber.cirrus.data.local.entity.PresetEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        PresetEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class CirrusDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun presetDao(): PresetDao

    companion object {
        const val NAME = "cirrus.db"

        /**
         * Adds the auto-title timestamp.
         *
         * Existing rows get NULL, which reads as "the user owns this title" — the conservative
         * choice, since a thread you named yourself must never be renamed behind your back.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN autoTitledAt INTEGER")
            }
        }
    }
}
