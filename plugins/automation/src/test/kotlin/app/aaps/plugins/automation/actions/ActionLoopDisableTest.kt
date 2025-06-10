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

class ActionLoopDisableTest : ActionsTestBase() {

    lateinit var sut: ActionLoopDisable

    @BeforeEach
    fun setup() {

        testPumpPlugin.pumpDescription.isTempBasalCapable = true
        `when`(rh.gs(app.aaps.core.ui.R.string.disableloop)).thenReturn("Disable loop")
        `when`(rh.gs(app.aaps.core.ui.R.string.loopisdisabled)).thenReturn("Loop is disabled")
        `when`(rh.gs(R.string.alreadydisabled)).thenReturn("Already disabled")

        sut = ActionLoopDisable(injector)
    }

    @Test
    fun friendlyNameTest() {
        assertThat(sut.friendlyName()).isEqualTo(app.aaps.core.ui.R.string.disableloop)
    }

    @Test
    fun shortDescriptionTest() {
        assertThat(sut.shortDescription()).isEqualTo("Disable loop")
    }

    @Test
    fun iconTest() {
        assertThat(sut.icon()).isEqualTo(R.drawable.ic_stop_24dp)
    }

    @Test
    fun isValidTest() {
        assertThat(sut.isValid()).isEqualTo(true)
    }

    @Test fun doActionTest() {
        `when`(loop.allowedNextModes()).thenReturn(listOf(RM.Mode.DISABLED_LOOP))
        sut.doAction(object : Callback() {
            override fun run() {
            }
        })
        Mockito.verify(loop, Mockito.times(1)).handleRunningModeChange(
            newRM = RM.Mode.DISABLED_LOOP,
            action = app.aaps.core.data.ue.Action.LOOP_DISABLED,
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
            newRM = RM.Mode.DISABLED_LOOP,
            action = app.aaps.core.data.ue.Action.LOOP_DISABLED,
            source = Sources.Automation,
            listValues = emptyList(),
            profile = validProfile
        )
    }
}
