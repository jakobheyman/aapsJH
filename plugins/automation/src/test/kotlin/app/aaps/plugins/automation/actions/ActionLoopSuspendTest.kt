package app.aaps.plugins.automation.actions

import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.queue.Callback
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputDuration
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

class ActionLoopSuspendTest : ActionsTestBase() {

    lateinit var sut: ActionLoopSuspend

    @BeforeEach
    fun setup() {

        `when`(rh.gs(app.aaps.core.ui.R.string.suspendloop)).thenReturn("Suspend loop")
        `when`(rh.gs(R.string.suspendloopforXmin)).thenReturn("Suspend loop for %d min")
        `when`(rh.gs(R.string.alreadysuspended)).thenReturn("Already suspended")

        sut = ActionLoopSuspend(injector)
    }

    @Test fun friendlyNameTest() {
        assertThat(sut.friendlyName()).isEqualTo(app.aaps.core.ui.R.string.suspendloop)
    }

    @Test fun shortDescriptionTest() {
        sut.minutes = InputDuration(30, InputDuration.TimeUnit.MINUTES)
        assertThat(sut.shortDescription()).isEqualTo("Suspend loop for 30 min")
    }

    @Test fun iconTest() {
        assertThat(sut.icon()).isEqualTo(R.drawable.ic_pause_circle_outline_24dp)
    }

    @Test
    fun isValidTest() {
        assertThat(sut.isValid()).isEqualTo(true)
    }

    @Test fun doActionTest() {
        `when`(loop.allowedNextModes()).thenReturn(listOf(RM.Mode.SUSPENDED_BY_USER))
        sut.doAction(object : Callback() {
            override fun run() {
            }
        })
        Mockito.verify(loop, Mockito.times(1)).handleRunningModeChange(
            newRM = RM.Mode.SUSPENDED_BY_USER,
            action = app.aaps.core.data.ue.Action.SUSPEND,
            source = Sources.Automation,
            durationInMinutes = sut.minutes.getMinutes(),
            listValues = listOf(ValueWithUnit.Minute(sut.minutes.getMinutes())),
            profile = validProfile
        )

        // mode not allowed, no new invocation
        `when`(loop.allowedNextModes()).thenReturn(emptyList())
        sut.doAction(object : Callback() {
            override fun run() {}
        })
        Mockito.verify(loop, Mockito.times(1)).handleRunningModeChange(
            newRM = RM.Mode.SUSPENDED_BY_USER,
            action = app.aaps.core.data.ue.Action.SUSPEND,
            source = Sources.Automation,
            durationInMinutes = sut.minutes.getMinutes(),
            listValues = listOf(ValueWithUnit.Minute(sut.minutes.getMinutes())),
            profile = validProfile
        )
    }
    @Test fun applyTest() {
        val a = ActionLoopSuspend(injector)
        a.minutes = InputDuration(20, InputDuration.TimeUnit.MINUTES)
        val b = ActionLoopSuspend(injector)
        b.apply(a)
        assertThat(b.minutes.getMinutes().toLong()).isEqualTo(20)
    }

    @Test fun hasDialogTest() {
        val a = ActionLoopSuspend(injector)
        assertThat(a.hasDialog()).isTrue()
    }
}
