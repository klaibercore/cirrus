package dev.klaiber.cirrus

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.klaiber.cirrus.domain.TurnController
import dev.klaiber.cirrus.domain.agents.AgentScheduler
import dev.klaiber.cirrus.domain.memory.ConsolidationScheduler
import dev.klaiber.cirrus.service.GenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class CirrusApp : Application(), Configuration.Provider {

    @Inject lateinit var turnController: TurnController

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var agentScheduler: AgentScheduler

    @Inject lateinit var consolidationScheduler: ConsolidationScheduler

    /**
     * Workers are constructed by Hilt, not by WorkManager's default factory.
     *
     * This is why the manifest disables `WorkManagerInitializer`: WorkManager must not initialise
     * itself before Hilt has injected the factory, or the first scheduled agent fails to
     * instantiate and the run is lost.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** Lives as long as the process does, which is exactly what this watch needs. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        // The service is what stops Android freezing the process mid-answer, so it has to be up
        // before the app can be backgrounded. Main.immediate starts it on the same turn of the
        // loop that started generating, which is still inside the window where a foreground
        // service may be started.
        scope.launch {
            turnController.turns
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { generating -> if (generating) GenerationService.start(this@CirrusApp) }
        }

        // Re-book everything on every start. WorkManager persists its queue, but a reboot, an
        // update or a "force stop" can leave it out of step with the database, and a schedule that
        // silently stopped firing is the one failure a scheduled agent must not have.
        scope.launch {
            agentScheduler.syncAll()
            consolidationScheduler.sync()
        }
    }
}
