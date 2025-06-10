package app.aaps.plugins.automation.actions

import androidx.annotation.DrawableRes
import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.Callback
import app.aaps.plugins.automation.R
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject

class ActionLoopResume(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var loop: Loop
    @Inject lateinit var profileFunction: ProfileFunction

    override fun friendlyName(): Int = app.aaps.core.ui.R.string.resumeloop
    override fun shortDescription(): String = rh.gs(app.aaps.core.ui.R.string.resumeloop)
    @DrawableRes override fun icon(): Int = R.drawable.ic_replay_24dp

    val disposable = CompositeDisposable()

    override fun doAction(callback: Callback) {
        val profile = profileFunction.getProfile() ?: return
        if (loop.allowedNextModes().contains(RM.Mode.RESUME)) {
            val result = loop.handleRunningModeChange(
                newRM = RM.Mode.RESUME,
                action = app.aaps.core.data.ue.Action.RESUME,
                source = Sources.Automation,
                listValues = emptyList(),
                profile = profile
            )
            callback.result(instantiator.providePumpEnactResult().success(result).comment(app.aaps.core.ui.R.string.ok)).run()
        } else {
            callback.result(instantiator.providePumpEnactResult().success(true).comment(R.string.notsuspended)).run()
        }
    }

    override fun isValid(): Boolean = true
}