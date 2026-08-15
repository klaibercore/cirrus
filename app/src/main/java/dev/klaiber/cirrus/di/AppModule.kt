package dev.klaiber.cirrus.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.klaiber.cirrus.domain.tools.ShellToolSet
import dev.klaiber.cirrus.domain.tools.shell.CalendarTool
import dev.klaiber.cirrus.domain.tools.shell.CleanWorkspaceTool
import dev.klaiber.cirrus.domain.tools.shell.DateTimeTool
import dev.klaiber.cirrus.domain.tools.shell.InstallAppTool
import dev.klaiber.cirrus.domain.tools.shell.ListAppsTool
import dev.klaiber.cirrus.domain.tools.shell.OpenAppTool
import dev.klaiber.cirrus.domain.tools.shell.RunCommandTool
import dev.klaiber.cirrus.domain.tools.shell.ShellRunner
import dev.klaiber.cirrus.domain.tools.shell.ShellWorkspace
import dev.klaiber.cirrus.domain.tools.shell.SystemInfoTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // The API adds fields over time; unknown ones must not break parsing.
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The shell's scratch directory.
     *
     * Under `cacheDir` on purpose: Android may reclaim it under storage pressure, which is exactly
     * the right fate for work nobody asked to keep, and it stays out of backups without any further
     * arrangement.
     */
    @Provides
    @Singleton
    fun provideShellWorkspace(@ApplicationContext context: Context): ShellWorkspace =
        ShellWorkspace(File(context.cacheDir, "shell-workspace"))

    @Provides
    @Singleton
    fun provideShellRunner(workspace: ShellWorkspace): ShellRunner = ShellRunner(workspace)

    /** Assembled by hand so that "which of these can act on the phone?" has one obvious answer. */
    @Provides
    @Singleton
    fun provideShellToolSet(
        runCommand: RunCommandTool,
        cleanWorkspace: CleanWorkspaceTool,
        dateTime: DateTimeTool,
        calendar: CalendarTool,
        systemInfo: SystemInfoTool,
        listApps: ListAppsTool,
        openApp: OpenAppTool,
        installApp: InstallAppTool,
    ): ShellToolSet = ShellToolSet(
        device = listOf(dateTime, calendar, systemInfo, runCommand, cleanWorkspace),
        apps = listOf(listApps, openApp, installApp),
    )

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        context.preferencesDataStoreFile("cirrus_settings")
    }
}
