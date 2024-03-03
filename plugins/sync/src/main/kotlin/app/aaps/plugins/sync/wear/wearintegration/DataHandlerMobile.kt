package app.aaps.plugins.sync.wear.wearintegration

import android.app.NotificationManager
import android.content.Context
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.BCR
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TDD
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusStepSize
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.events.EventWearUpdateGui
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.generateCOBString
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.toStringShort
import app.aaps.core.objects.extensions.valueToUnits
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.core.objects.wizard.QuickWizardEntry
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.plugins.sync.R
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min

@Singleton
class DataHandlerMobile @Inject constructor(
    aapsSchedulers: AapsSchedulers,
    private val injector: HasAndroidInjector,
    private val context: Context,
    private val rxBus: RxBus,
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val sp: SP,
    private val preferences: Preferences,
    private val config: Config,
    private val iobCobCalculator: IobCobCalculator,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val loop: Loop,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val receiverStatusStore: ReceiverStatusStore,
    private val quickWizard: QuickWizard,
    private val trendCalculator: TrendCalculator,
    private val dateUtil: DateUtil,
    private val constraintChecker: ConstraintsChecker,
    private val uel: UserEntryLogger,
    private val activePlugin: ActivePlugin,
    private val commandQueue: CommandQueue,
    private val fabricPrivacy: FabricPrivacy,
    private val uiInteraction: UiInteraction,
    private val persistenceLayer: PersistenceLayer,
    private val importExportPrefs: ImportExportPrefs,
    private val decimalFormatter: DecimalFormatter
) {

    private val disposable = CompositeDisposable()

    private var lastBolusWizard: BolusWizard? = null

    init {
        // From Wear
        disposable += rxBus
            .toObservable(EventData.ActionPong::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "Pong received from ${it.sourceNodeId}")
                           fabricPrivacy.logCustom("WearOS_${it.apiLevel}")
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.CancelBolus::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "CancelBolus received from ${it.sourceNodeId}")
                           activePlugin.activePump.stopBolusDelivering()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.OpenLoopRequestConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "OpenLoopRequestConfirmed received from ${it.sourceNodeId}")
                           loop.acceptChangeRequest()
                           (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(Constants.notificationID)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionResendData::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ResendData received from ${it.sourceNodeId}")
                           resendData(it.from)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionPumpStatus::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionPumpStatus received from ${it.sourceNodeId}")
                           rxBus.send(
                               EventMobileToWear(
                                   EventData.ConfirmAction(
                                       rh.gs(R.string.pump_status).uppercase(),
                                       activePlugin.activePump.shortStatus(false),
                                       returnCommand = null
                                   )
                               )
                           )
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionLoopStatus::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionLoopStatus received from ${it.sourceNodeId}")
                           rxBus.send(
                               EventMobileToWear(
                                   EventData.ConfirmAction(
                                       rh.gs(R.string.loop_status).uppercase(),
                                       "$targetsStatus\n\n$loopStatus\n\n$oAPSResultStatus",
                                       returnCommand = null
                                   )
                               )
                           )
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionTddStatus::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe {
                aapsLogger.debug(LTag.WEAR, "ActionTddStatus received from ${it.sourceNodeId}")
                handleTddStatus()
            }
        disposable += rxBus
            .toObservable(EventData.ActionProfileSwitchSendInitialData::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionProfileSwitchSendInitialData received $it from ${it.sourceNodeId}")
                           handleProfileSwitchSendInitialData()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionProfileSwitchPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionProfileSwitchPreCheck received $it from ${it.sourceNodeId}")
                           handleProfileSwitchPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionProfileSwitchConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionProfileSwitchConfirmed received $it from ${it.sourceNodeId}")
                           doProfileSwitch(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionTempTargetPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionTempTargetPreCheck received $it from ${it.sourceNodeId}")
                           handleTempTargetPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionTempTargetConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionTempTargetConfirmed received $it from ${it.sourceNodeId}")
                           doTempTarget(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionBolusPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionBolusPreCheck received $it from ${it.sourceNodeId}")
                           handleBolusPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionBolusConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionBolusConfirmed received $it from ${it.sourceNodeId}")
                           doBolus(it.insulin, it.carbs, null, 0, null)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionECarbsPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionECarbsPreCheck received $it from ${it.sourceNodeId}")
                           handleECarbsPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionECarbsConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionECarbsConfirmed received $it from ${it.sourceNodeId}")
                           doECarbs(it.carbs, it.carbsTime, it.duration)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionFillPresetPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionFillPresetPreCheck received $it from ${it.sourceNodeId}")
                           handleFillPresetPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionFillPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionFillPreCheck received $it from ${it.sourceNodeId}")
                           handleFillPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionFillConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionFillConfirmed received $it from ${it.sourceNodeId}")
                           if (constraintChecker.applyBolusConstraints(ConstraintObject(it.insulin, aapsLogger)).value() - it.insulin != 0.0) {
                               ToastUtils.showToastInUiThread(context, "aborting: previously applied constraint changed")
                               sendError("aborting: previously applied constraint changed")
                           } else
                               doFillBolus(it.insulin)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionQuickWizardPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionQuickWizardPreCheck received $it from ${it.sourceNodeId}")
                           handleQuickWizardPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionWizardPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionWizardPreCheck received $it from ${it.sourceNodeId}")
                           handleWizardPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionWizardConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionWizardConfirmed received $it from ${it.sourceNodeId}")
                           lastBolusWizard?.let { lastBolusWizard ->
                               if (lastBolusWizard.timeStamp == it.timeStamp) { //use last calculation as confirmed string matches
                                   doBolus(lastBolusWizard.calculatedTotalInsulin, lastBolusWizard.carbs, null, 0, lastBolusWizard.createBolusCalculatorResult())
                               }
                           }
                           lastBolusWizard = null
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.SnoozeAlert::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "SnoozeAlert received $it from ${it.sourceNodeId}")
                           uiInteraction.stopAlarm("Muted from wear")
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.WearException::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "WearException received $it from ${it.sourceNodeId}")
                           fabricPrivacy.logWearException(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionHeartRate::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ handleHeartRate(it) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionStepsRate::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ handleStepsCount(it) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionGetCustomWatchface::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "Custom Watch face ${it.customWatchface} received from ${it.sourceNodeId}")
                           handleGetCustomWatchface(it)
                       }, fabricPrivacy::logException)
    }

    private fun handleTddStatus() {
        val activePump = activePlugin.activePump
        var message: String
        // check if DB up to date
        val dummies: MutableList<TDD> = LinkedList()
        val historyList = getTDDList(dummies)
        if (isOldData(historyList)) {
            message = rh.gs(app.aaps.core.ui.R.string.tdd_old_data) + ", "
            //if pump is not busy: try to fetch data
            if (activePump.isBusy()) {
                message += rh.gs(app.aaps.core.ui.R.string.pump_busy)
            } else {
                message += rh.gs(R.string.pump_fetching_data)
                commandQueue.loadTDDs(object : Callback() {
                    override fun run() {
                        val dummies1: MutableList<TDD> = LinkedList()
                        val historyList1 = getTDDList(dummies1)
                        val reloadMessage =
                            if (isOldData(historyList1))
                                rh.gs(R.string.pump_old_data) + "\n" + generateTDDMessage(historyList1, dummies1)
                            else
                                generateTDDMessage(historyList1, dummies1)
                        rxBus.send(
                            EventMobileToWear(
                                EventData.ConfirmAction(
                                    rh.gs(app.aaps.core.ui.R.string.tdd),
                                    reloadMessage,
                                    returnCommand = null
                                )
                            )
                        )
                    }
                })
            }
        } else { // if up to date: prepare, send (check if CPP is activated -> add CPP stats)
            message = generateTDDMessage(historyList, dummies)
        }
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.tdd),
                    message,
                    returnCommand = null
                )
            )
        )
    }

    private fun handleWizardPreCheck(command: EventData.ActionWizardPreCheck) {
        val pump = activePlugin.activePump
        if (!pump.isInitialized() || pump.isSuspended() || loop.isDisconnected) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_pump_not_available))
            return
        }
        val carbsBeforeConstraints = command.carbs
        val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(carbsBeforeConstraints, aapsLogger)).value()
        if (carbsAfterConstraints - carbsBeforeConstraints != 0) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_carbs_constraint))
            return
        }
        val percentage = command.percentage
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        if (profile == null) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_active_profile))
            return
        }
        val bgReading = iobCobCalculator.ads.actualBg()
        if (bgReading == null) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_actual_bg))
            return
        }
        val cobInfo = iobCobCalculator.getCobInfo("Wizard wear")
        if (cobInfo.displayCob == null) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_cob))
            return
        }
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())

        val bolusWizard = BolusWizard(injector).doCalc(
            profile = profile,
            profileName = profileName,
            tempTarget = tempTarget,
            carbs = carbsAfterConstraints,
            cob = cobInfo.displayCob!!,
            bg = bgReading.valueToUnits(profileFunction.getUnits()),
            correction = 0.0,
            percentageCorrection = percentage,
            useBg = preferences.get(BooleanKey.WearWizardBg),
            useCob = preferences.get(BooleanKey.WearWizardCob),
            includeBolusIOB = preferences.get(BooleanKey.WearWizardIob),
            includeBasalIOB = preferences.get(BooleanKey.WearWizardIob),
            useSuperBolus = false,
            useTT = preferences.get(BooleanKey.WearWizardTt),
            useTrend = preferences.get(BooleanKey.WearWizardTrend),
            useAlarm = false
        )
        val insulinAfterConstraints = bolusWizard.insulinAfterConstraints
        val minStep = pump.pumpDescription.pumpType.determineCorrectBolusStepSize(insulinAfterConstraints)
        if (abs(insulinAfterConstraints - bolusWizard.calculatedTotalInsulin) >= minStep) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_constraint_bolus_size, bolusWizard.calculatedTotalInsulin))
            return
        }
        if (bolusWizard.calculatedTotalInsulin <= 0 && bolusWizard.carbs <= 0) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_insulin_required))
            return
        }
        val message =
            rh.gs(R.string.wizard_result, bolusWizard.calculatedTotalInsulin, bolusWizard.carbs) + "\n_____________\n" + bolusWizard.explainShort()
        lastBolusWizard = bolusWizard
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), message,
                    returnCommand = EventData.ActionWizardConfirmed(bolusWizard.timeStamp)
                )
            )
        )
    }

    private fun handleQuickWizardPreCheck(command: EventData.ActionQuickWizardPreCheck) {
        val actualBg = iobCobCalculator.ads.actualBg()
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        val quickWizardEntry = quickWizard.get(command.guid)
        if (quickWizardEntry == null) {
            sendError(rh.gs(R.string.quick_wizard_not_available))
            return
        }
        if (actualBg == null) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_actual_bg))
            return
        }
        if (profile == null) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_active_profile))
            return
        }
        val cobInfo = iobCobCalculator.getCobInfo("QuickWizard wear")
        if (cobInfo.displayCob == null) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_cob))
            return
        }
        val pump = activePlugin.activePump
        if (!pump.isInitialized() || pump.isSuspended() || loop.isDisconnected) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_pump_not_available))
            return
        }

        val wizard = quickWizardEntry.doCalc(profile, profileName, actualBg)

        val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(quickWizardEntry.carbs(), aapsLogger)).value()
        if (carbsAfterConstraints != quickWizardEntry.carbs()) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_carbs_constraint))
            return
        }
        val insulinAfterConstraints = wizard.insulinAfterConstraints
        val minStep = pump.pumpDescription.pumpType.determineCorrectBolusStepSize(insulinAfterConstraints)
        if (abs(insulinAfterConstraints - wizard.calculatedTotalInsulin) >= minStep) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_constraint_bolus_size, wizard.calculatedTotalInsulin))
            return
        }

        val message = rh.gs(R.string.quick_wizard_message, quickWizardEntry.buttonText(), wizard.calculatedTotalInsulin, quickWizardEntry.carbs()) +
            "\n_____________\n" + wizard.explainShort()

        lastBolusWizard = wizard
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), message,
                    returnCommand = EventData.ActionWizardConfirmed(wizard.timeStamp)
                )
            )
        )
    }

    private fun handleBolusPreCheck(command: EventData.ActionBolusPreCheck) {
        val insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(command.insulin, aapsLogger)).value()
        val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(command.carbs, aapsLogger)).value()
        val pump = activePlugin.activePump
        if (insulinAfterConstraints > 0 && (!pump.isInitialized() || pump.isSuspended() || loop.isDisconnected)) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_pump_not_available))
            return
        }
        var message = ""
        message += rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + insulinAfterConstraints + rh.gs(R.string.units_short) + "\n"
        message += rh.gs(app.aaps.core.ui.R.string.carbs) + ": " + carbsAfterConstraints + rh.gs(R.string.grams_short)
        if (insulinAfterConstraints - command.insulin != 0.0 || carbsAfterConstraints - command.carbs != 0)
            message += "\n" + rh.gs(app.aaps.core.ui.R.string.constraint_applied)
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), message,
                    returnCommand = EventData.ActionBolusConfirmed(insulinAfterConstraints, carbsAfterConstraints)
                )
            )
        )
    }

    private fun handleECarbsPreCheck(command: EventData.ActionECarbsPreCheck) {
        val startTimeStamp = System.currentTimeMillis() + T.mins(command.carbsTimeShift.toLong()).msecs()
        val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(command.carbs, aapsLogger)).value()
        var message = rh.gs(app.aaps.core.ui.R.string.carbs) + ": " + carbsAfterConstraints + rh.gs(R.string.grams_short) +
            "\n" + rh.gs(app.aaps.core.ui.R.string.time) + ": " + dateUtil.timeString(startTimeStamp) +
            "\n" + rh.gs(app.aaps.core.ui.R.string.duration) + ": " + command.duration + rh.gs(R.string.hour_short)
        if (carbsAfterConstraints - command.carbs != 0) {
            message += "\n" + rh.gs(app.aaps.core.ui.R.string.constraint_applied)
        }
        if (carbsAfterConstraints <= 0) {
            sendError(rh.gs(app.aaps.core.ui.R.string.carb_equal_zero_no_action))
            return
        }
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), message,
                    returnCommand = EventData.ActionECarbsConfirmed(carbsAfterConstraints, startTimeStamp, command.duration)
                )
            )
        )
    }

    private fun handleFillPresetPreCheck(command: EventData.ActionFillPresetPreCheck) {
        val amount: Double = when (command.button) {
            1    -> preferences.get(DoubleKey.ActionsFillButton1)
            2    -> preferences.get(DoubleKey.ActionsFillButton2)
            3    -> preferences.get(DoubleKey.ActionsFillButton3)
            else -> return
        }
        val insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(amount, aapsLogger)).value()
        var message = rh.gs(app.aaps.core.ui.R.string.prime_fill) + ": " + insulinAfterConstraints + rh.gs(R.string.units_short)
        if (insulinAfterConstraints - amount != 0.0) message += "\n" + rh.gs(app.aaps.core.ui.R.string.constraint_applied)
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), message,
                    returnCommand = EventData.ActionFillConfirmed(insulinAfterConstraints)
                )
            )
        )
    }

    private fun handleFillPreCheck(command: EventData.ActionFillPreCheck) {
        val insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(command.insulin, aapsLogger)).value()
        var message = rh.gs(app.aaps.core.ui.R.string.prime_fill) + ": " + insulinAfterConstraints + rh.gs(R.string.units_short)
        if (insulinAfterConstraints - command.insulin != 0.0) message += "\n" + rh.gs(app.aaps.core.ui.R.string.constraint_applied)
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), message,
                    returnCommand = EventData.ActionFillConfirmed(insulinAfterConstraints)
                )
            )
        )
    }

    private fun handleProfileSwitchSendInitialData() {
        val activeProfileSwitch = persistenceLayer.getEffectiveProfileSwitchActiveAt(dateUtil.now())
        if (activeProfileSwitch != null) { // read CPP values
            rxBus.send(
                EventMobileToWear(EventData.ActionProfileSwitchOpenActivity(T.msecs(activeProfileSwitch.originalTimeshift).hours().toInt(), activeProfileSwitch.originalPercentage))
            )
        } else {
            sendError(rh.gs(R.string.no_active_profile))
            return
        }

    }

    private fun handleProfileSwitchPreCheck(command: EventData.ActionProfileSwitchPreCheck) {
        val activeProfileSwitch = persistenceLayer.getEffectiveProfileSwitchActiveAt(dateUtil.now())
        if (activeProfileSwitch == null) {
            sendError(rh.gs(R.string.no_active_profile))
        }
        if (command.percentage < Constants.CPP_MIN_PERCENTAGE || command.percentage > Constants.CPP_MAX_PERCENTAGE) {
            sendError(rh.gs(app.aaps.core.ui.R.string.valueoutofrange, "Profile-Percentage"))
        }
        if (command.timeShift < 0 || command.timeShift > 23) {
            sendError(rh.gs(app.aaps.core.ui.R.string.valueoutofrange, "Profile-Timeshift"))
        }
        val message = rh.gs(R.string.profile_message, command.timeShift, command.percentage)
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), message,
                    returnCommand = EventData.ActionProfileSwitchConfirmed(command.timeShift, command.percentage)
                )
            )
        )
    }

    private fun handleTempTargetPreCheck(action: EventData.ActionTempTargetPreCheck) {
        val title = rh.gs(app.aaps.core.ui.R.string.confirm).uppercase()
        var message = ""
        val presetIsMGDL = profileFunction.getUnits() == GlucoseUnit.MGDL
        when (action.command) {
            EventData.ActionTempTargetPreCheck.TempTargetCommand.PRESET_ACTIVITY -> {
                val activityTTDuration = preferences.get(IntKey.OverviewActivityDuration)
                val activityTT = preferences.get(UnitDoubleKey.OverviewActivityTarget)
                val reason = rh.gs(app.aaps.core.ui.R.string.activity)
                message += rh.gs(R.string.wear_action_tempt_preset_message, reason, activityTT, activityTTDuration)
                rxBus.send(
                    EventMobileToWear(
                        EventData.ConfirmAction(
                            title, message,
                            returnCommand = EventData.ActionTempTargetConfirmed(presetIsMGDL, activityTTDuration, activityTT, activityTT)
                        )
                    )
                )
            }

            EventData.ActionTempTargetPreCheck.TempTargetCommand.PRESET_HYPO     -> {
                val hypoTTDuration = preferences.get(IntKey.OverviewHypoDuration)
                val hypoTT = preferences.get(UnitDoubleKey.OverviewHypoTarget)
                val reason = rh.gs(app.aaps.core.ui.R.string.hypo)
                message += rh.gs(R.string.wear_action_tempt_preset_message, reason, hypoTT, hypoTTDuration)
                rxBus.send(
                    EventMobileToWear(
                        EventData.ConfirmAction(
                            title, message,
                            returnCommand = EventData.ActionTempTargetConfirmed(presetIsMGDL, hypoTTDuration, hypoTT, hypoTT)
                        )
                    )
                )
            }

            EventData.ActionTempTargetPreCheck.TempTargetCommand.PRESET_EATING   -> {
                val eatingSoonTTDuration = preferences.get(IntKey.OverviewEatingSoonDuration)
                val eatingSoonTT = preferences.get(UnitDoubleKey.OverviewEatingSoonTarget)
                val reason = rh.gs(app.aaps.core.ui.R.string.eatingsoon)
                message += rh.gs(R.string.wear_action_tempt_preset_message, reason, eatingSoonTT, eatingSoonTTDuration)
                rxBus.send(
                    EventMobileToWear(
                        EventData.ConfirmAction(
                            title, message,
                            returnCommand = EventData.ActionTempTargetConfirmed(presetIsMGDL, eatingSoonTTDuration, eatingSoonTT, eatingSoonTT)
                        )
                    )
                )
            }

            EventData.ActionTempTargetPreCheck.TempTargetCommand.CANCEL          -> {
                message += rh.gs(R.string.wear_action_tempt_cancel_message)
                rxBus.send(
                    EventMobileToWear(
                        EventData.ConfirmAction(
                            title, message,
                            returnCommand = EventData.ActionTempTargetConfirmed(true, 0, 0.0, 0.0)
                        )
                    )
                )
            }

            EventData.ActionTempTargetPreCheck.TempTargetCommand.MANUAL          -> {
                if (profileFunction.getUnits() == GlucoseUnit.MGDL != action.isMgdl) {
                    sendError(rh.gs(R.string.wear_action_tempt_unit_error))
                    return
                }
                if (action.duration == 0) {
                    message += rh.gs(R.string.wear_action_tempt_zero_message)
                    rxBus.send(
                        EventMobileToWear(
                            EventData.ConfirmAction(
                                title, message,
                                returnCommand = EventData.ActionTempTargetConfirmed(true, 0, 0.0, 0.0)
                            )
                        )
                    )
                } else {
                    var low = action.low
                    var high = action.high
                    if (!action.isMgdl) {
                        low *= Constants.MMOLL_TO_MGDL
                        high *= Constants.MMOLL_TO_MGDL
                    }
                    if (low < HardLimits.LIMIT_TEMP_MIN_BG[0] || low > HardLimits.LIMIT_TEMP_MIN_BG[1]) {
                        sendError(rh.gs(R.string.wear_action_tempt_min_bg_error))
                        return
                    }
                    if (high < HardLimits.LIMIT_TEMP_MAX_BG[0] || high > HardLimits.LIMIT_TEMP_MAX_BG[1]) {
                        sendError(rh.gs(R.string.wear_action_tempt_max_bg_error))
                        return
                    }
                    message += if (low == high) rh.gs(R.string.wear_action_tempt_manual_message, action.low, action.duration)
                    else rh.gs(R.string.wear_action_tempt_manual_range_message, action.low, action.high, action.duration)
                    rxBus.send(
                        EventMobileToWear(
                            EventData.ConfirmAction(
                                title, message,
                                returnCommand = EventData.ActionTempTargetConfirmed(presetIsMGDL, action.duration, action.low, action.high)
                            )
                        )
                    )
                }
            }
        }
    }

    private fun QuickWizardEntry.toWear(): EventData.QuickWizard.QuickWizardEntry =
        EventData.QuickWizard.QuickWizardEntry(
            guid = guid(),
            buttonText = buttonText(),
            carbs = carbs(),
            validFrom = validFrom(),
            validTo = validTo()
        )

    fun resendData(from: String) {
        aapsLogger.debug(LTag.WEAR, "Sending data to wear from $from")
        // SingleBg
        iobCobCalculator.ads.lastBg()?.let { rxBus.send(EventMobileToWear(getSingleBG(it, iobCobCalculator.ads))) }
        // Preferences
        rxBus.send(
            EventMobileToWear(
                EventData.Preferences(
                    timeStamp = System.currentTimeMillis(),
                    wearControl = preferences.get(BooleanKey.WearControl),
                    unitsMgdl = profileFunction.getUnits() == GlucoseUnit.MGDL,
                    bolusPercentage = preferences.get(IntKey.OverviewBolusPercentage),
                    maxCarbs = preferences.get(IntKey.SafetyMaxCarbs),
                    maxBolus = preferences.get(DoubleKey.SafetyMaxBolus),
                    insulinButtonIncrement1 = preferences.get(DoubleKey.OverviewInsulinButtonIncrement1),
                    insulinButtonIncrement2 = preferences.get(DoubleKey.OverviewInsulinButtonIncrement2),
                    carbsButtonIncrement1 = preferences.get(IntKey.OverviewCarbsButtonIncrement1),
                    carbsButtonIncrement2 = preferences.get(IntKey.OverviewCarbsButtonIncrement2)
                )
            )
        )
        // QuickWizard
        rxBus.send(
            EventMobileToWear(
                EventData.QuickWizard(
                    ArrayList(quickWizard.list().filter { it.forDevice(QuickWizardEntry.DEVICE_WATCH) }.map { it.toWear() })
                )
            )
        )
        // GraphData
        iobCobCalculator.ads.getBucketedDataTableCopy()?.let { bucketedData ->
            rxBus.send(EventMobileToWear(EventData.GraphData(ArrayList(bucketedData.map { getSingleBG(it, null) }))))
        }
        // Treatments
        sendTreatments()
        // Status
        // Keep status last. Wear start refreshing after status received
        sendStatus(from)
    }

    private fun sendTreatments() {
        val now = System.currentTimeMillis()
        val startTimeWindow = now - (60000 * 60 * 5.5).toLong()
        val basals = arrayListOf<EventData.TreatmentData.Basal>()
        val temps = arrayListOf<EventData.TreatmentData.TempBasal>()
        val boluses = arrayListOf<EventData.TreatmentData.Treatment>()
        val predictions = arrayListOf<EventData.SingleBg>()
        val profile = profileFunction.getProfile() ?: return
        var beginBasalSegmentTime = startTimeWindow
        var runningTime = startTimeWindow
        var beginBasalValue = profile.getBasal(beginBasalSegmentTime)
        var endBasalValue = beginBasalValue
        var tb1 = processedTbrEbData.getTempBasalIncludingConvertedExtended(runningTime)
        var tb2: TB?
        var tbBefore = beginBasalValue
        var tbAmount = beginBasalValue
        var tbStart = runningTime
        if (tb1 != null) {
            val profileTB = profileFunction.getProfile(runningTime)
            if (profileTB != null) {
                tbAmount = tb1.convertedToAbsolute(runningTime, profileTB)
                tbStart = runningTime
            }
        }
        while (runningTime < now) {
            val profileTB = profileFunction.getProfile(runningTime) ?: return
            //basal rate
            endBasalValue = profile.getBasal(runningTime)
            if (endBasalValue != beginBasalValue) {
                //push the segment we recently left
                basals.add(EventData.TreatmentData.Basal(beginBasalSegmentTime, runningTime, beginBasalValue))

                //begin new Basal segment
                beginBasalSegmentTime = runningTime
                beginBasalValue = endBasalValue
            }

            //temps
            tb2 = processedTbrEbData.getTempBasalIncludingConvertedExtended(runningTime)
            when {
                tb1 == null && tb2 == null -> {
                    //no temp stays no temp
                }

                tb1 != null && tb2 == null -> {
                    //temp is over -> push it
                    temps.add(EventData.TreatmentData.TempBasal(tbStart, tbBefore, runningTime, endBasalValue, tbAmount))
                    tb1 = null
                }

                tb1 == null && tb2 != null -> {
                    //temp begins
                    tb1 = tb2
                    tbStart = runningTime
                    tbBefore = endBasalValue
                    tbAmount = tb1.convertedToAbsolute(runningTime, profileTB)
                }

                tb1 != null && tb2 != null -> {
                    val currentAmount = tb2.convertedToAbsolute(runningTime, profileTB)
                    if (currentAmount != tbAmount) {
                        temps.add(EventData.TreatmentData.TempBasal(tbStart, tbBefore, runningTime, currentAmount, tbAmount))
                        tbStart = runningTime
                        tbBefore = tbAmount
                        tbAmount = currentAmount
                        tb1 = tb2
                    }
                }
            }
            runningTime += (5 * 60 * 1000L)
        }
        if (beginBasalSegmentTime != runningTime) {
            //push the remaining segment
            basals.add(EventData.TreatmentData.Basal(beginBasalSegmentTime, runningTime, beginBasalValue))
        }
        if (tb1 != null) {
            tb2 = processedTbrEbData.getTempBasalIncludingConvertedExtended(now) //use "now" to express current situation
            if (tb2 == null) {
                //express the cancelled temp by painting it down one minute early
                temps.add(EventData.TreatmentData.TempBasal(tbStart, tbBefore, now - 60 * 1000, endBasalValue, tbAmount))
            } else {
                //express currently running temp by painting it a bit into the future
                val profileNow = profileFunction.getProfile(now)
                val currentAmount = tb2.convertedToAbsolute(now, profileNow!!)
                if (currentAmount != tbAmount) {
                    temps.add(EventData.TreatmentData.TempBasal(tbStart, tbBefore, now, tbAmount, tbAmount))
                    temps.add(EventData.TreatmentData.TempBasal(now, tbAmount, runningTime + 5 * 60 * 1000, currentAmount, currentAmount))
                } else {
                    temps.add(EventData.TreatmentData.TempBasal(tbStart, tbBefore, runningTime + 5 * 60 * 1000, tbAmount, tbAmount))
                }
            }
        } else {
            tb2 = processedTbrEbData.getTempBasalIncludingConvertedExtended(now) //use "now" to express current situation
            if (tb2 != null) {
                //onset at the end
                val profileTB = profileFunction.getProfile(runningTime)
                val currentAmount = tb2.convertedToAbsolute(runningTime, profileTB!!)
                temps.add(EventData.TreatmentData.TempBasal(now - 60 * 1000, endBasalValue, runningTime + 5 * 60 * 1000, currentAmount, currentAmount))
            }
        }
        persistenceLayer.getBolusesFromTimeIncludingInvalid(startTimeWindow, true).blockingGet()
            .stream()
            .filter { (_, _, _, _, _, _, _, _, _, type) -> type !== BS.Type.PRIMING }
            .forEach { (_, _, _, isValid, _, _, timestamp, _, amount, type) -> boluses.add(EventData.TreatmentData.Treatment(timestamp, amount, 0.0, type === BS.Type.SMB, isValid)) }
        persistenceLayer.getCarbsFromTimeExpanded(startTimeWindow, true)
            .forEach { (_, _, _, isValid, _, _, timestamp, _, _, amount) -> boluses.add(EventData.TreatmentData.Treatment(timestamp, 0.0, amount, false, isValid)) }
        val finalLastRun = loop.lastRun
        if (finalLastRun?.request?.hasPredictions == true && finalLastRun.constraintsProcessed != null) {
            val predArray = finalLastRun.constraintsProcessed!!.predictionsAsGv
            if (predArray.isNotEmpty())
                for (bg in predArray) if (bg.value > 39)
                    predictions.add(
                        EventData.SingleBg(
                            timeStamp = bg.timestamp,
                            glucoseUnits = GlucoseUnit.MGDL.asText,
                            sgv = bg.value,
                            high = 0.0,
                            low = 0.0,
                            color = predictionColor(context, bg)
                        )
                    )
        }
        rxBus.send(EventMobileToWear(EventData.TreatmentData(temps, basals, boluses, predictions)))
    }

    private fun predictionColor(context: Context?, data: GV): Int {
        return when (data.sourceSensor) {
            SourceSensor.IOB_PREDICTION   -> rh.gac(context, app.aaps.core.ui.R.attr.iobColor)
            SourceSensor.COB_PREDICTION   -> rh.gac(context, app.aaps.core.ui.R.attr.cobColor)
            SourceSensor.A_COB_PREDICTION -> -0x7f000001 and rh.gac(context, app.aaps.core.ui.R.attr.cobColor)
            SourceSensor.UAM_PREDICTION   -> rh.gac(context, app.aaps.core.ui.R.attr.uamColor)
            SourceSensor.ZT_PREDICTION    -> rh.gac(context, app.aaps.core.ui.R.attr.ztColor)
            else                          -> rh.gac(context, app.aaps.core.ui.R.attr.defaultTextColor)
        }
    }

    private fun sendStatus(caller: String) {
        val profile = profileFunction.getProfile()
        var status = rh.gs(app.aaps.core.ui.R.string.noprofile)
        var iobSum = ""
        var iobDetail = ""
        var cobString = ""
        var currentBasal = ""
        var bgiString = ""
        if (config.appInitialized && profile != null) {
            val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            iobSum = decimalFormatter.to2Decimal(bolusIob.iob + basalIob.basaliob)
            iobDetail = "(${decimalFormatter.to2Decimal(bolusIob.iob)}|${decimalFormatter.to2Decimal(basalIob.basaliob)})"
            cobString = iobCobCalculator.getCobInfo("WatcherUpdaterService").generateCOBString(decimalFormatter)
            currentBasal =
                processedTbrEbData.getTempBasalIncludingConvertedExtended(System.currentTimeMillis())?.toStringShort(rh) ?: rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, profile.getBasal())

            //bgi
            val bgi = -(bolusIob.activity + basalIob.activity) * 5 * profileUtil.fromMgdlToUnits(profile.getIsfMgdl("DataHandlerMobile $caller"))
            bgiString = "" + (if (bgi >= 0) "+" else "") + decimalFormatter.to1Decimal(bgi)
            status = generateStatusString(profile)
        }

        //batteries
        val phoneBattery = receiverStatusStore.batteryLevel
        val rigBattery = processedDeviceStatusData.uploaderStatus.trim { it <= ' ' }
        //OpenAPS status
        val openApsStatus =
            if (config.APS) loop.lastRun?.let { if (it.lastTBREnact != 0L) it.lastTBREnact else -1 } ?: -1
            else processedDeviceStatusData.openApsTimestamp

        rxBus.send(
            EventMobileToWear(
                EventData.Status(
                    externalStatus = status,
                    iobSum = iobSum,
                    iobDetail = iobDetail,
                    cob = cobString,
                    currentBasal = currentBasal,
                    battery = phoneBattery.toString(),
                    rigBattery = rigBattery,
                    openApsStatus = openApsStatus,
                    bgi = bgiString,
                    batteryLevel = if (phoneBattery >= 30) 1 else 0
                )
            )
        )
    }

    private fun deltaString(deltaMGDL: Double, deltaMMOL: Double, units: GlucoseUnit): String {
        var deltaString = if (deltaMGDL >= 0) "+" else "-"
        deltaString += if (units == GlucoseUnit.MGDL) {
            decimalFormatter.to0Decimal(abs(deltaMGDL))
        } else {
            decimalFormatter.to1Decimal(abs(deltaMMOL))
        }
        return deltaString
    }

    private fun deltaStringDetailed(deltaMGDL: Double, deltaMMOL: Double, units: GlucoseUnit): String {
        var deltaStringDetailed = if (deltaMGDL >= 0) "+" else "-"
        deltaStringDetailed += if (units == GlucoseUnit.MGDL) {
            decimalFormatter.to1Decimal(abs(deltaMGDL))
        } else {
            decimalFormatter.to2Decimal(abs(deltaMMOL))
        }
        return deltaStringDetailed
    }

    private fun getSingleBG(glucoseValue: InMemoryGlucoseValue, autosensDataStore: AutosensDataStore?): EventData.SingleBg {
        val glucoseStatus = glucoseStatusProvider.getGlucoseStatusData(true)
        val units = profileFunction.getUnits()
        val lowLine = profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewLowMark), units)
        val highLine = profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewHighMark), units)

        return EventData.SingleBg(
            timeStamp = glucoseValue.timestamp,
            sgvString = profileUtil.stringInCurrentUnitsDetect(glucoseValue.recalculated),
            glucoseUnits = units.asText,
            slopeArrow = (autosensDataStore?.let { ads -> trendCalculator.getTrendArrow(ads) } ?: TrendArrow.NONE).symbol,
            delta = glucoseStatus?.let { deltaString(it.delta, it.delta * Constants.MGDL_TO_MMOLL, units) } ?: "--",
            deltaDetailed = glucoseStatus?.let { deltaStringDetailed(it.delta, it.delta * Constants.MGDL_TO_MMOLL, units) } ?: "--",
            avgDelta = glucoseStatus?.let { deltaString(it.shortAvgDelta, it.shortAvgDelta * Constants.MGDL_TO_MMOLL, units) } ?: "--",
            avgDeltaDetailed = glucoseStatus?.let { deltaStringDetailed(it.shortAvgDelta, it.shortAvgDelta * Constants.MGDL_TO_MMOLL, units) } ?: "--",
            sgvLevel = if (glucoseValue.recalculated > highLine) 1L else if (glucoseValue.recalculated < lowLine) -1L else 0L,
            sgv = glucoseValue.recalculated,
            high = highLine,
            low = lowLine,
            color = 0,
            deltaMgdl = glucoseStatus?.delta,
            avgDeltaMgdl = glucoseStatus?.shortAvgDelta
        )
    }

    //Check for Temp-Target:
    private
    val targetsStatus: String
        get() {
            var ret = rh.gs(app.aaps.core.ui.R.string.loopstatus_targets) + "\n"
            if (!config.APS) {
                return rh.gs(R.string.target_only_aps_mode)
            }
            val profile = profileFunction.getProfile() ?: return rh.gs(R.string.no_profile)
            //Check for Temp-Target:
            val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
            if (tempTarget != null) {
                val target = profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.lowTarget, GlucoseUnit.MGDL)
                ret += rh.gs(R.string.temp_target) + ": " + target
                ret += "\n" + rh.gs(R.string.until) + ": " + dateUtil.timeString(tempTarget.end)
                ret += "\n\n"
            }
            ret += rh.gs(R.string.default_range) + ": "
            ret += profileUtil.toTargetRangeString(profile.getTargetLowMgdl(), profile.getTargetHighMgdl(), GlucoseUnit.MGDL)
            ret += " " + rh.gs(R.string.target) + ": " + profileUtil.fromMgdlToStringInUnits(profile.getTargetMgdl())
            return ret
        }

    private
    val oAPSResultStatus: String
        get() {
            var ret = rh.gs(app.aaps.core.ui.R.string.loopstatus_OAPS_result) + "\n"
            if (!config.APS)
                return rh.gs(R.string.aps_only)
            val usedAPS = activePlugin.activeAPS
            val result = usedAPS.lastAPSResult ?: return rh.gs(R.string.last_aps_result_na)
            ret += if (!result.isChangeRequested) {
                rh.gs(app.aaps.core.ui.R.string.nochangerequested) + "\n"
            } else if (result.rate == 0.0 && result.duration == 0) {
                rh.gs(app.aaps.core.ui.R.string.cancel_temp) + "\n"
            } else {
                rh.gs(R.string.rate_duration, result.rate, result.rate / activePlugin.activePump.baseBasalRate * 100, result.duration) + "\n"
            }
            ret += "\n" + rh.gs(app.aaps.core.ui.R.string.reason) + ": " + result.reason
            return ret
        }

    // decide if enabled/disabled closed/open; what Plugin as APS?
    private
    val loopStatus: String
        get() {
            var ret = ""
            // decide if enabled/disabled closed/open; what Plugin as APS?
            if (loop.isEnabled()) {
                ret += if (constraintChecker.isClosedLoopAllowed().value()) {
                    rh.gs(R.string.loop_status_closed) + "\n"
                } else {
                    rh.gs(R.string.loop_status_open) + "\n"
                }
                val aps = activePlugin.activeAPS
                ret += rh.gs(R.string.aps) + ": " + (aps as PluginBase).name
                val lastRun = loop.lastRun
                if (lastRun != null) {
                    ret += "\n" + rh.gs(R.string.last_run) + ": " + dateUtil.timeString(lastRun.lastAPSRun)
                    if (lastRun.lastTBREnact != 0L) ret += "\n" + rh.gs(R.string.last_enact) + ": " + dateUtil.timeString(lastRun.lastTBREnact)
                }
            } else {
                ret += rh.gs(R.string.loop_status_disabled) + "\n"
            }
            return ret
        }

    private fun isOldData(historyList: List<TDD>): Boolean {
        val startsYesterday = activePlugin.activePump.pumpDescription.supportsTDDs
        val df: DateFormat = SimpleDateFormat("dd.MM.", Locale.getDefault())
        return historyList.size < 3 || df.format(Date(historyList[0].timestamp)) != df.format(Date(System.currentTimeMillis() - if (startsYesterday) 1000 * 60 * 60 * 24 else 0))
    }

    private fun getTDDList(returnDummies: List<TDD>): MutableList<TDD> {
        var historyList = persistenceLayer.getLastTotalDailyDoses(10, false).toMutableList()
        //var historyList = databaseHelper.getTDDs().toMutableList()
        historyList = historyList.subList(0, min(10, historyList.size))
        // fill single gaps - only needed for Dana*R data
        val dummies: MutableList<TDD> = returnDummies.toMutableList()
        val df: DateFormat = SimpleDateFormat("dd.MM.", Locale.getDefault())
        for (i in 0 until historyList.size - 1) {
            val elem1 = historyList[i]
            val elem2 = historyList[i + 1]
            if (df.format(Date(elem1.timestamp)) != df.format(Date(elem2.timestamp + 25 * 60 * 60 * 1000))) {
                val dummy = TDD(timestamp = elem1.timestamp - T.hours(24).msecs(), bolusAmount = elem1.bolusAmount / 2, basalAmount = elem1.basalAmount / 2)
                dummies.add(dummy)
                elem1.basalAmount /= 2.0
                elem1.bolusAmount /= 2.0
            }
        }
        historyList.addAll(dummies)
        historyList.sortWith { lhs, rhs -> (rhs.timestamp - lhs.timestamp).toInt() }
        return historyList
    }

    private val TDD.total
        get() = if (totalAmount > 0) totalAmount else basalAmount + bolusAmount

    private fun generateTDDMessage(historyList: MutableList<TDD>, dummies: List<TDD>): String {
        val profile = profileFunction.getProfile() ?: return rh.gs(R.string.no_profile)
        if (historyList.isEmpty()) {
            return rh.gs(R.string.no_history)
        }
        val df: DateFormat = SimpleDateFormat("dd.MM.", Locale.getDefault())
        var message = ""
        val refTDD = profile.baseBasalSum() * 2
        if (df.format(Date(historyList[0].timestamp)) == df.format(Date())) {
            val tdd = historyList[0].total
            historyList.removeAt(0)

            message += rh.gs(R.string.today) + ": " + rh.gs(R.string.tdd_line, tdd, 100 * tdd / refTDD) + "\n"
            message += "\n"
        }
        var weighted03 = 0.0
        var weighted05 = 0.0
        var weighted07 = 0.0
        historyList.reverse()
        for ((i, record) in historyList.withIndex()) {
            val tdd = record.total
            if (i == 0) {
                weighted03 = tdd
                weighted05 = tdd
                weighted07 = tdd
            } else {
                weighted07 = weighted07 * 0.3 + tdd * 0.7
                weighted05 = weighted05 * 0.5 + tdd * 0.5
                weighted03 = weighted03 * 0.7 + tdd * 0.3
            }
        }
        message += rh.gs(R.string.weighted) + ":\n"
        message += "0.3: " + rh.gs(R.string.tdd_line, weighted03, 100 * weighted03 / refTDD) + "\n"
        message += "0.5: " + rh.gs(R.string.tdd_line, weighted05, 100 * weighted05 / refTDD) + "\n"
        message += "0.7: " + rh.gs(R.string.tdd_line, weighted07, 100 * weighted07 / refTDD) + "\n"
        message += "\n"
        historyList.reverse()
        // add TDDs:
        for (record in historyList) {
            val tdd = record.total
            message += df.format(Date(record.timestamp)) + " " + rh.gs(R.string.tdd_line, tdd, 100 * tdd / refTDD)
            message += (if (dummies.contains(record)) "x" else "") + "\n"
        }
        return message
    }

    private fun generateStatusString(profile: Profile?): String {
        var status = ""
        profile ?: return rh.gs(app.aaps.core.ui.R.string.noprofile)
        if (!loop.isEnabled()) status += rh.gs(R.string.disabled_loop) + "\n"
        return status
    }

    private fun doTempTarget(command: EventData.ActionTempTargetConfirmed) {
        if (command.duration != 0)
            disposable += persistenceLayer.insertAndCancelCurrentTemporaryTarget(
                temporaryTarget = TT(
                    timestamp = System.currentTimeMillis(),
                    duration = TimeUnit.MINUTES.toMillis(command.duration.toLong()),
                    reason = TT.Reason.WEAR,
                    lowTarget = profileUtil.convertToMgdl(command.low, profileFunction.getUnits()),
                    highTarget = profileUtil.convertToMgdl(command.high, profileFunction.getUnits())
                ),
                action = Action.TT,
                source = Sources.Wear,
                note = null,
                listValues = listOf(
                    ValueWithUnit.TETTReason(TT.Reason.WEAR),
                    ValueWithUnit.fromGlucoseUnit(command.low, profileFunction.getUnits()),
                    ValueWithUnit.fromGlucoseUnit(command.high, profileFunction.getUnits()).takeIf { command.low != command.high },
                    ValueWithUnit.Minute(command.duration)
                )
            ).subscribe()
        else
            disposable += persistenceLayer.cancelCurrentTemporaryTargetIfAny(
                timestamp = dateUtil.now(),
                action = Action.CANCEL_TT,
                source = Sources.Wear,
                note = null,
                listValues = listOf(ValueWithUnit.TETTReason(TT.Reason.WEAR))
            )
                .subscribe()
    }

    private fun doBolus(amount: Double, carbs: Int, carbsTime: Long?, carbsDuration: Int, bolusCalculatorResult: BCR?) {
        val detailedBolusInfo = DetailedBolusInfo()
        detailedBolusInfo.insulin = amount
        detailedBolusInfo.carbs = carbs.toDouble()
        detailedBolusInfo.bolusType = BS.Type.NORMAL
        detailedBolusInfo.carbsTimestamp = carbsTime
        detailedBolusInfo.carbsDuration = T.hours(carbsDuration.toLong()).msecs()
        if (detailedBolusInfo.insulin > 0 || detailedBolusInfo.carbs > 0) {
            val action = when {
                amount == 0.0     -> Action.CARBS
                carbs == 0        -> Action.BOLUS
                carbsDuration > 0 -> Action.EXTENDED_CARBS
                else              -> Action.TREATMENT
            }
            uel.log(
                action = action, source = Sources.Wear,
                listValues = listOf(ValueWithUnit.Insulin(amount).takeIf { amount != 0.0 },
                                    ValueWithUnit.Gram(carbs).takeIf { carbs != 0 },
                                    ValueWithUnit.Hour(carbsDuration).takeIf { carbsDuration != 0 })
            )
            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                override fun run() {
                    if (!result.success)
                        sendError(rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror) + "\n" + result.comment)
                }
            })
            bolusCalculatorResult?.let { persistenceLayer.insertOrUpdateBolusCalculatorResult(it).blockingGet() }
        }
    }

    private fun doFillBolus(amount: Double) {
        val detailedBolusInfo = DetailedBolusInfo()
        detailedBolusInfo.insulin = amount
        detailedBolusInfo.bolusType = BS.Type.PRIMING
        uel.log(
            action = Action.PRIME_BOLUS, source = Sources.Wear,
            listValues = listOf(ValueWithUnit.Insulin(amount).takeIf { amount != 0.0 })
        )
        commandQueue.bolus(detailedBolusInfo, object : Callback() {
            override fun run() {
                if (!result.success) {
                    sendError(rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror) + "\n" + result.comment)
                }
            }
        })
    }

    private fun doECarbs(carbs: Int, carbsTime: Long, duration: Int) {
        uel.log(
            action = if (duration == 0) Action.CARBS else Action.EXTENDED_CARBS,
            source = Sources.Wear,
            listValues = listOf(ValueWithUnit.Timestamp(carbsTime),
                                ValueWithUnit.Gram(carbs),
                                ValueWithUnit.Hour(duration).takeIf { duration != 0 })
        )
        doBolus(0.0, carbs, carbsTime, duration, null)
    }

    private fun doProfileSwitch(command: EventData.ActionProfileSwitchConfirmed) {
        //check for validity
        if (command.percentage < Constants.CPP_MIN_PERCENTAGE || command.percentage > Constants.CPP_MAX_PERCENTAGE)
            return
        if (command.timeShift < 0 || command.timeShift > 23)
            return
        profileFunction.getProfile() ?: return
        //send profile to pump
        profileFunction.createProfileSwitch(
            durationInMinutes = 0,
            percentage = command.percentage,
            timeShiftInHours = command.timeShift,
            action = Action.PROFILE_SWITCH,
            source = Sources.Wear,
            listValues = listOf(
                ValueWithUnit.Percent(command.percentage),
                ValueWithUnit.Hour(command.timeShift).takeIf { command.timeShift != 0 }
            )
        )
    }

    @Synchronized private fun sendError(errorMessage: String) {
        rxBus.send(EventMobileToWear(EventData.ConfirmAction(rh.gs(app.aaps.core.ui.R.string.error), errorMessage, returnCommand = EventData.Error(dateUtil.now())))) // ignore return path
    }

    /** Stores heart rate events coming from the Wear device. */
    private fun handleHeartRate(actionHeartRate: EventData.ActionHeartRate) {
        aapsLogger.debug(LTag.WEAR, "Heart rate received $actionHeartRate from ${actionHeartRate.sourceNodeId}")
        val hr = HR(
            duration = actionHeartRate.duration,
            timestamp = actionHeartRate.timestamp,
            beatsPerMinute = actionHeartRate.beatsPerMinute,
            device = actionHeartRate.device
        )
        disposable += persistenceLayer.insertOrUpdateHeartRate(hr).subscribe()
    }

    private fun handleStepsCount(actionStepsRate: EventData.ActionStepsRate) {
        aapsLogger.debug(LTag.WEAR, "Steps count received $actionStepsRate from ${actionStepsRate.sourceNodeId}")
        val stepsCount = SC(
            duration = actionStepsRate.duration,
            timestamp = actionStepsRate.timestamp,
            steps5min = actionStepsRate.steps5min,
            steps10min = actionStepsRate.steps10min,
            steps15min = actionStepsRate.steps15min,
            steps30min = actionStepsRate.steps30min,
            steps60min = actionStepsRate.steps60min,
            steps180min = actionStepsRate.steps180min,
            device = actionStepsRate.device
        )
        disposable += persistenceLayer.insertOrUpdateStepsCount(stepsCount).subscribe()
    }

    private fun handleGetCustomWatchface(command: EventData.ActionGetCustomWatchface) {
        val customWatchface = command.customWatchface
        aapsLogger.debug(LTag.WEAR, "Custom Watchface received from ${command.sourceNodeId}")
        val cwfData = customWatchface.customWatchfaceData
        rxBus.send(EventWearUpdateGui(cwfData, command.exportFile))
        val watchfaceName = sp.getString(app.aaps.core.utils.R.string.key_wear_cwf_watchface_name, "")
        val authorVersion = sp.getString(app.aaps.core.utils.R.string.key_wear_cwf_author_version, "")
        if (cwfData.metadata[CwfMetadataKey.CWF_NAME] != watchfaceName || cwfData.metadata[CwfMetadataKey.CWF_AUTHOR_VERSION] != authorVersion) {
            sp.putString(app.aaps.core.utils.R.string.key_wear_cwf_watchface_name, cwfData.metadata[CwfMetadataKey.CWF_NAME] ?: "")
            sp.putString(app.aaps.core.utils.R.string.key_wear_cwf_author_version, cwfData.metadata[CwfMetadataKey.CWF_AUTHOR_VERSION] ?: "")
            sp.putString(app.aaps.core.utils.R.string.key_wear_cwf_filename, cwfData.metadata[CwfMetadataKey.CWF_FILENAME] ?: "")
        }

        if (command.exportFile)
            importExportPrefs.exportCustomWatchface(cwfData, command.withDate)
    }

}
