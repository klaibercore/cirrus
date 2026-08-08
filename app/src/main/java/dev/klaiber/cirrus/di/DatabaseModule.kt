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
import dev.klaiber.cirrus.data.local.dao.ConversationDao
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
            .build()

    @Provides
    fun provideConversationDao(database: CirrusDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideMessageDao(database: CirrusDatabase): MessageDao = database.messageDao()

    @Provides
    fun providePresetDao(database: CirrusDatabase): PresetDao = database.presetDao()

    @Provides
    @Singleton
    fun provideEntityMapper(json: Json): EntityMapper = EntityMapper(json)
}
