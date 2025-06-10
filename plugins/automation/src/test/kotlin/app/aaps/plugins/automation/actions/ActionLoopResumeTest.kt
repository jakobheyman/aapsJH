package app.aaps.plugins.automation.actions

import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.queue.Callback
import app.aaps.plugins.automation.R
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

class ActionLoopResumeTest : ActionsTestBase() {

    lateinit var sut: ActionLoopResume

    @BeforeEach
    fun setup() {

        `when`(rh.gs(app.aaps.core.ui.R.string.resumeloop)).thenReturn("Resume loop")
        `when`(rh.gs(R.string.notsuspended)).thenReturn("Not suspended")

        sut = ActionLoopResume(injector)
    }

    @Test fun friendlyNameTest() {
        assertThat(sut.friendlyName()).isEqualTo(app.aaps.core.ui.R.string.resumeloop)
    }

    @Test fun shortDescriptionTest() {
        assertThat(sut.shortDescription()).isEqualTo("Resume loop")
    }

    @Test fun iconTest() {
        assertThat(sut.icon()).isEqualTo(R.drawable.ic_replay_24dp)
    }

    @Test
    fun isValidTest() {
        assertThat(sut.isValid()).isEqualTo(true)
    }

    @Test fun doActionTest() {
        `when`(loop.allowedNextModes()).thenReturn(listOf(RM.Mode.RESUME))
        sut.doAction(object : Callback() {
            override fun run() {
            }
        })
        Mockito.verify(loop, Mockito.times(1)).handleRunningModeChange(
            newRM = RM.Mode.RESUME,
            action = app.aaps.core.data.ue.Action.RESUME,
            source = Sources.Automation,
            listValues = emptyList(),
            profile = validProfile
        )

        // mode not allowed, no new invocation
        `when`(loop.allowedNextModes()).thenReturn(emptyList())
        sut.doAction(object : Callback() {
            override fun run() {}
        })
        Mockito.verify(loop, Mockito.times(1)).handleRunningModeChange(
            newRM = RM.Mode.RESUME,
            action = app.aaps.core.data.ue.Action.RESUME,
            source = Sources.Automation,
            listValues = emptyList(),
            profile = validProfile
        )
    }
}
