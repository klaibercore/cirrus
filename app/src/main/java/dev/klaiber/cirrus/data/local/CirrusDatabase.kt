package dev.klaiber.cirrus.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = true,
)
abstract class CirrusDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun presetDao(): PresetDao

    companion object {
        const val NAME = "cirrus.db"
    }
}
