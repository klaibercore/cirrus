package dev.klaiber.cirrus

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.klaiber.cirrus.domain.TurnController
import dev.klaiber.cirrus.service.GenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class CirrusApp : Application() {

    @Inject lateinit var turnController: TurnController

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
    }
}
