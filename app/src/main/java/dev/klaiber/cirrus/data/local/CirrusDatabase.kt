package dev.klaiber.cirrus.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.klaiber.cirrus.data.local.dao.AgentDao
import dev.klaiber.cirrus.data.local.dao.ConversationDao
import dev.klaiber.cirrus.data.local.dao.MemoryDao
import dev.klaiber.cirrus.data.local.dao.MessageDao
import dev.klaiber.cirrus.data.local.dao.PresetDao
import dev.klaiber.cirrus.data.local.entity.AgentEntity
import dev.klaiber.cirrus.data.local.entity.AttachmentEntity
import dev.klaiber.cirrus.data.local.entity.ConversationEntity
import dev.klaiber.cirrus.data.local.entity.MemoryEntity
import dev.klaiber.cirrus.data.local.entity.MessageEntity
import dev.klaiber.cirrus.data.local.entity.PresetEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        PresetEntity::class,
        MemoryEntity::class,
        AgentEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class CirrusDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun presetDao(): PresetDao
    abstract fun memoryDao(): MemoryDao
    abstract fun agentDao(): AgentDao

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

        /**
         * Adds memories and agents.
         *
         * Both are new tables rather than columns, so nothing existing is touched and the
         * migration cannot lose a conversation. The column types and NOT NULL flags have to match
         * what Room generates for the entities exactly, or the identity hash check fails at open.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memories` (
                        `id` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `sourceConversationId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastRecalledAt` INTEGER,
                        `recallCount` INTEGER NOT NULL,
                        `pinned` INTEGER NOT NULL,
                        `archived` INTEGER NOT NULL,
                        `confidence` REAL NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_archived` ON `memories` (`archived`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_pinned` ON `memories` (`pinned`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_updatedAt` ON `memories` (`updatedAt`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agents` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `model` TEXT,
                        `minuteOfDay` INTEGER NOT NULL,
                        `daysMask` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `toolsEnabled` INTEGER NOT NULL,
                        `notifyOnFinish` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastRunAt` INTEGER,
                        `lastStatus` TEXT,
                        `lastSummary` TEXT,
                        `lastConversationId` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agents_enabled` ON `agents` (`enabled`)")
            }
        }
    }
}
