package app.aaps.pump.danars.comm

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.pump.dana.DanaPump
import app.aaps.pump.danars.encryption.BleEncryption
import javax.inject.Inject

open class DanaRSPacketBolusSetStepBolusStop @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val rh: ResourceHelper,
    private val danaPump: DanaPump
) : DanaRSPacket() {

    init {
        opCode = BleEncryption.DANAR_PACKET__OPCODE_BOLUS__SET_STEP_BOLUS_STOP
    }

    override fun handleMessage(data: ByteArray) {
        val result = intFromBuff(data, 0, 1)
        @Suppress("LiftReturnOrAssignment")
        if (result == 0) {
            aapsLogger.debug(LTag.PUMPCOMM, "Result OK")
            failed = false
        } else {
            aapsLogger.error("Result Error: $result")
            failed = true
        }
        val bolusingEvent = EventOverviewBolusProgress
        danaPump.bolusStopped = true
        if (!danaPump.bolusStopForced) {
            // delivery ended without user intervention
            danaPump.bolusingTreatment?.insulin = danaPump.bolusAmountToBeDelivered
            bolusingEvent.status = rh.gs(app.aaps.pump.dana.R.string.overview_bolusprogress_delivered)
            bolusingEvent.percent = 100
        } else {
            bolusingEvent.status = rh.gs(app.aaps.pump.dana.R.string.overview_bolusprogress_stoped)
        }
        rxBus.send(bolusingEvent)
    }

    override val friendlyName: String = "BOLUS__SET_STEP_BOLUS_STOP"
}