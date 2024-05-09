package app.aaps.plugins.aps.openAPSAutoISF

import android.content.SharedPreferences
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.keys.AdaptiveIntentPreference
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.validators.AdaptiveDoublePreference
import app.aaps.core.validators.AdaptiveIntPreference
import app.aaps.core.validators.AdaptiveSwitchPreference
import app.aaps.core.validators.AdaptiveUnitPreference
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`

class OpenAPSAutoISFPluginTest : TestBaseWithProfile() {

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var determineBasalSMB: DetermineBasalAutoISF
    @Mock lateinit var sharedPrefs: SharedPreferences
    @Mock lateinit var bgQualityCheck: BgQualityCheck
    @Mock lateinit var profiler: Profiler
    private lateinit var openAPSAutoISFPlugin: OpenAPSAutoISFPlugin

    init {
        addInjector {
            if (it is AdaptiveDoublePreference) {
                it.profileUtil = profileUtil
                it.preferences = preferences
                it.sharedPrefs = sharedPrefs
            }
            if (it is AdaptiveIntPreference) {
                it.profileUtil = profileUtil
                it.preferences = preferences
                it.sharedPrefs = sharedPrefs
                it.config = config
            }
            if (it is AdaptiveIntentPreference) {
                it.preferences = preferences
                it.sharedPrefs = sharedPrefs
            }
            if (it is AdaptiveUnitPreference) {
                it.profileUtil = profileUtil
                it.preferences = preferences
                it.sharedPrefs = sharedPrefs
            }
            if (it is AdaptiveSwitchPreference) {
                it.preferences = preferences
                it.sharedPrefs = sharedPrefs
                it.config = config
            }
        }
    }

    @BeforeEach fun prepare() {
        openAPSAutoISFPlugin = OpenAPSAutoISFPlugin(
            injector, aapsLogger, rxBus, constraintChecker, rh, profileFunction, profileUtil, config, activePlugin,
            iobCobCalculator, hardLimits, preferences, dateUtil, processedTbrEbData, persistenceLayer, glucoseStatusProvider,
            bgQualityCheck, determineBasalSMB, profiler
        )
    }

    @Test
    fun specialEnableConditionTest() {
        assertThat(openAPSAutoISFPlugin.specialEnableCondition()).isTrue()
    }

    @Test
    fun specialShowInListConditionTest() {
        assertThat(openAPSAutoISFPlugin.specialShowInListCondition()).isTrue()
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        openAPSAutoISFPlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }

    @Test
    fun withinISFlimitsTest() {
        var autoIsfMin = 0.7
        var autoIsfMax = 1.2
        var sens = 1.1  // from Autosens
        val origin_sens = ""
        var ttSet = false
        var exerciseMode = false
        var targetBg = 120.0
        val normalTarget = 100
        assertThat(openAPSAutoISFPlugin.withinISFlimits(1.7, autoIsfMin, autoIsfMax, sens, origin_sens, ttSet, exerciseMode, targetBg, normalTarget)).isEqualTo(1.2) // upper limit
        assertThat(openAPSAutoISFPlugin.withinISFlimits(0.5, autoIsfMin, autoIsfMax, sens, origin_sens, ttSet, exerciseMode, targetBg, normalTarget)).isEqualTo(0.7) // lower limit
        sens = 1.5  // from Autosens
        assertThat(openAPSAutoISFPlugin.withinISFlimits(1.7, autoIsfMin, autoIsfMax, sens, origin_sens, ttSet, exerciseMode, targetBg, normalTarget)).isEqualTo(1.5) // autosens 1.5 wins
        sens = 0.5  // from Autosens
        assertThat(openAPSAutoISFPlugin.withinISFlimits(0.5, autoIsfMin, autoIsfMax, sens, origin_sens, ttSet, exerciseMode, targetBg, normalTarget)).isEqualTo(0.5) // autosens 0.5 wins
        exerciseMode = true
        ttSet = true
        assertThat(openAPSAutoISFPlugin.withinISFlimits(0.5, autoIsfMin, autoIsfMax, sens, origin_sens, ttSet, exerciseMode, targetBg, normalTarget)).isEqualTo(0.35) // exercise mode
    }

    @Test
    fun determine_varSMBratioTest() {
        `when`(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatio)).thenReturn(0.55)
        `when`(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMin)).thenReturn(0.4)
        `when`(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMax)).thenReturn(0.6)
        `when`(preferences.get(UnitDoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange)).thenReturn(20.0)
        //`when`(preferences.get(DoubleKey.ApsAutoIsfSmbMaxRangeExtension)).thenReturn(1.0)

        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(100, 90.0, "fullLoop")).isEqualTo(0.55)
        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(180, 90.0, "fullLoop")).isEqualTo(0.6)
        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(100, 90.0, "enforced")).isEqualTo(0.5)
        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(80, 90.0, "enforced")).isEqualTo(0.4)
        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(180, 90.0, "enforced")).isEqualTo(0.6)
        `when`(preferences.get(UnitDoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange)).thenReturn(0.0)
        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(180, 90.0, "enforced")).isEqualTo(0.55)
    }

    @Test
    fun interpolateTest() {
        `when`(preferences.get(DoubleKey.ApsAutoIsfLowBgWeight)).thenReturn(10.0)
        `when`(preferences.get(DoubleKey.ApsAutoIsfHighBgWeight)).thenReturn(1.0)
        assertThat(openAPSAutoISFPlugin.interpolate(45.0,  "bg")).isEqualTo(-5.0)
        assertThat(openAPSAutoISFPlugin.interpolate(55.0,  "bg")).isEqualTo(-5.0)
        assertThat(openAPSAutoISFPlugin.interpolate(100.0,  "bg")).isEqualTo(0.0)
        assertThat(openAPSAutoISFPlugin.interpolate(130.0,  "bg")).isEqualTo(0.25)
        assertThat(openAPSAutoISFPlugin.interpolate(230.0,  "bg")).isEqualTo(0.7)
    }

    @Test
    fun loop_smbTest() {
        val profile = OapsProfileAutoIsf(
            dia = 0.0, // not used
            min_5m_carbimpact = 0.0, // not used
            max_iob = 10.0,
            max_daily_basal = 0.5,
            max_basal = 0.0,
            min_bg = 90.0,
            max_bg = 90.0,
            target_bg = 90.0,
            carb_ratio = 10.0,
            sens = 100.0,
            autosens_adjust_targets = false, // not used
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = false,
            low_temptarget_lowers_sensitivity = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens), // was false,
            sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget),
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = false,
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = false,
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB = true,
            enableSMB_with_temptarget = true,
            allowSMB_with_high_temptarget = false,
            enableSMB_always = true,
            enableSMB_after_carbs = true,
            maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
            maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = 0.1,
            carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = activePlugin.activePump.baseBasalRate,
            temptargetSet = true,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = "mg/dl",
            variable_sens = 111.1,
            autoISF_version = "3.0",
            enable_autoISF = true,
            autoISF_max = 1.5,
            autoISF_min = 0.7,
            bgAccel_ISF_weight = 0.0,
            bgBrake_ISF_weight = 0.0,
            enable_pp_ISF_always = true,
            pp_ISF_hours = 3,
            pp_ISF_weight = 0.0,
            delta_ISFrange_weight = 0.0,
            lower_ISFrange_weight = 0.0,
            higher_ISFrange_weight = 0.0,
            enable_dura_ISF_with_COB = true,
            dura_ISF_weight = 0.0,
            smb_delivery_ratio = 0.5,
            smb_delivery_ratio_min = 0.6,
            smb_delivery_ratio_max = 1.0,
            smb_delivery_ratio_bg_range = 0.0,
            smb_max_range_extension = 1.0,
            enableSMB_EvenOn_OddOff = true,
            enableSMB_EvenOn_OddOff_always = true,
            iob_threshold_percent = 100,
            profile_percentage = 100
        )
        assertThat(openAPSAutoISFPlugin.loop_smb(false,  profile, 11.0, false, 11.1)).isEqualTo("AAPS")
        `when`(preferences.get(BooleanKey.ApsAutoIsfSmbOnEvenPt)).thenReturn(true)
        `when`(preferences.get(BooleanKey.ApsAutoIsfSmbOnEvenTt)).thenReturn(true)
        assertThat(openAPSAutoISFPlugin.loop_smb(true,  profile, 11.0, false, 11.1)).isEqualTo("fullLoop")
        assertThat(openAPSAutoISFPlugin.loop_smb(true,  profile, 11.0, true, 10.1)).isEqualTo("iobTH")
        profile.target_bg = 122.0
        assertThat(openAPSAutoISFPlugin.loop_smb(true,  profile, 11.0, false, 11.1)).isEqualTo("enforced")
        profile.target_bg = 91.8    //5.1
        profile.out_units = "mmol/L"
        assertThat(openAPSAutoISFPlugin.loop_smb(true,  profile, 11.0, false, 11.1)).isEqualTo("blocked")
        `when`(preferences.get(BooleanKey.ApsAutoIsfSmbOnEvenPt)).thenReturn(false)
        `when`(preferences.get(BooleanKey.ApsAutoIsfSmbOnEvenTt)).thenReturn(false)
        assertThat(openAPSAutoISFPlugin.loop_smb(true,  profile, 11.0, false, 11.1)).isEqualTo("AAPS")
    }

    @Test
    fun autoISFTest() {
        // TODO get profile
        val profile = profileFunction.getProfile(now)
        if ( profile == null) return

        val oapsProfile = OapsProfileAutoIsf(
            dia = 0.0, // not used
            min_5m_carbimpact = 0.0, // not used
            max_iob = 10.0,
            max_daily_basal = 0.5,
            max_basal = 0.0,
            min_bg = 91.0,
            max_bg = 91.0,
            target_bg = 91.0,
            carb_ratio = 10.0,
            sens = 100.0,
            autosens_adjust_targets = false, // not used
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = false,
            low_temptarget_lowers_sensitivity = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens), // was false,
            sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget),
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = false,
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = false,
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB = true,
            enableSMB_with_temptarget = true,
            allowSMB_with_high_temptarget = false,
            enableSMB_always = true,
            enableSMB_after_carbs = true,
            maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
            maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = 0.1,
            carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = activePlugin.activePump.baseBasalRate,
            temptargetSet = true,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = "mg/dl",
            variable_sens = 47.11,
            autoISF_version = "3.0",
            enable_autoISF = false,
            autoISF_max = 1.5,
            autoISF_min = 0.7,
            bgAccel_ISF_weight = 0.0,
            bgBrake_ISF_weight = 0.0,
            enable_pp_ISF_always = true,
            pp_ISF_hours = 3,
            pp_ISF_weight = 0.0,
            delta_ISFrange_weight = 0.0,
            lower_ISFrange_weight = 0.0,
            higher_ISFrange_weight = 0.0,
            enable_dura_ISF_with_COB = true,
            dura_ISF_weight = 0.0,
            smb_delivery_ratio = 0.5,
            smb_delivery_ratio_min = 0.6,
            smb_delivery_ratio_max = 1.0,
            smb_delivery_ratio_bg_range = 0.0,
            smb_max_range_extension = 1.0,
            enableSMB_EvenOn_OddOff = true,
            enableSMB_EvenOn_OddOff_always = true,
            iob_threshold_percent = 100,
            profile_percentage = 100
        )
        assertThat(openAPSAutoISFPlugin.autoISF(now, profile)).isEqualTo(47.11)                             // inactive
        `when`(oapsProfile.enable_autoISF).thenReturn(true)
        val glucose_status = glucoseStatusProvider.glucoseStatusData!!
        `when`(glucose_status.corrSqu).thenReturn(0.4711)
        assertThat(openAPSAutoISFPlugin.autoISF(now, profile)).isEqualTo(47.11)                             // bad parabola
        `when`(preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)).thenReturn(true)
        `when`(preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget)).thenReturn(160)
        assertThat(openAPSAutoISFPlugin.autoISF(now, profile)).isEqualTo(47.11 * 2.0)                       // exercise mode w/o AutoISF
        `when`(glucose_status.corrSqu).thenReturn(0.95)
        `when`(glucose_status.glucose).thenReturn(90.0)
        `when`(glucose_status.a0).thenReturn(90.3)
        `when`(glucose_status.a1).thenReturn(2.0)
        `when`(glucose_status.a2).thenReturn(3.0)
        `when`(glucose_status.bgAcceleration).thenReturn(2.0 * glucose_status.a2)
        `when`(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)).thenReturn(2.0)
        assertThat(openAPSAutoISFPlugin.autoISF(now, profile)).isEqualTo(47.11 * 2.0 * 2.0)                 // acce_ISF + exercise mode
        `when`(preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)).thenReturn(false)
        assertThat(openAPSAutoISFPlugin.autoISF(now, profile)).isEqualTo(47.11 * 2.0)                       // acce_ISF w/o exercise mode
       `when`(preferences.get(DoubleKey.ApsAutoIsfLowBgWeight)).thenReturn(2.0)
        assertThat(openAPSAutoISFPlugin.autoISF(now, profile)).isEqualTo(47.11 * 1.0)                       // bg_ISF strengthened by acce_ISF

    }
}
