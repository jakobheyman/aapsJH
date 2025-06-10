package app.aaps.plugins.automation.actions

import androidx.annotation.DrawableRes
import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.Callback
import app.aaps.plugins.automation.R
import dagger.android.HasAndroidInjector
import javax.inject.Inject

class ActionLoopClosed(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var loop: Loop
    @Inject lateinit var profileFunction: ProfileFunction

    override fun friendlyName(): Int = app.aaps.core.ui.R.string.closedloop
    override fun shortDescription(): String = rh.gs(app.aaps.core.ui.R.string.closedloop)
    @DrawableRes override fun icon(): Int = R.drawable.ic_play_circle_outline_24dp

    override fun doAction(callback: Callback) {
        val profile = profileFunction.getProfile() ?: return
        if (loop.allowedNextModes().contains(RM.Mode.CLOSED_LOOP)) {
            loop.handleRunningModeChange(
                newRM = RM.Mode.CLOSED_LOOP,
                action = app.aaps.core.data.ue.Action.CLOSED_LOOP_MODE,
                source = Sources.Automation,
                profile = profile
            )
            callback.result(instantiator.providePumpEnactResult().success(true).comment(app.aaps.core.ui.R.string.ok)).run()
        } else {
            callback.result(instantiator.providePumpEnactResult().success(true).comment(R.string.alreadyenabled)).run()
        }
    }

    override fun isValid(): Boolean = true
}