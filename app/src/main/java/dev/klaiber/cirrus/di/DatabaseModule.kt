package dev.klaiber.cirrus.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.klaiber.cirrus.data.local.CirrusDatabase
import dev.klaiber.cirrus.data.local.EntityMapper
import dev.klaiber.cirrus.data.local.dao.AgentDao
import dev.klaiber.cirrus.data.local.dao.AgentRunDao
import dev.klaiber.cirrus.data.local.dao.ConversationDao
import dev.klaiber.cirrus.data.local.dao.MemoryDao
import dev.klaiber.cirrus.data.local.dao.MessageDao
import dev.klaiber.cirrus.data.local.dao.PresetDao
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CirrusDatabase =
        Room.databaseBuilder(context, CirrusDatabase::class.java, CirrusDatabase.NAME)
            // Cascading deletes of messages/attachments rely on foreign keys being enforced.
            .addMigrations(
                CirrusDatabase.MIGRATION_1_2,
                CirrusDatabase.MIGRATION_2_3,
                CirrusDatabase.MIGRATION_3_4,
            )
            .build()

    @Provides
    fun provideConversationDao(database: CirrusDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideMessageDao(database: CirrusDatabase): MessageDao = database.messageDao()

    @Provides
    fun providePresetDao(database: CirrusDatabase): PresetDao = database.presetDao()

    @Provides
    fun provideMemoryDao(database: CirrusDatabase): MemoryDao = database.memoryDao()

    @Provides
    fun provideAgentDao(database: CirrusDatabase): AgentDao = database.agentDao()

    @Provides
    fun provideAgentRunDao(database: CirrusDatabase): AgentRunDao = database.agentRunDao()

    @Provides
    @Singleton
    fun provideEntityMapper(json: Json): EntityMapper = EntityMapper(json)
}
