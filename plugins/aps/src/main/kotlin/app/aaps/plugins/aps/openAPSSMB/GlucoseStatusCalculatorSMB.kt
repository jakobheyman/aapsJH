package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.plugins.aps.openAPSSMB.extensions.asRounded
import app.aaps.plugins.aps.openAPSSMB.extensions.log
import dagger.Reusable
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Reusable
class GlucoseStatusCalculatorSMB @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val decimalFormatter: DecimalFormatter
) : GlucoseStatusProvider {

    override val glucoseStatusData: GlucoseStatus?
        get() = getGlucoseStatusData(false)

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatusSMB? {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return null

        val sizeRecords = data.size
        if (sizeRecords == 0) {
            aapsLogger.debug(LTag.GLUCOSE, "sizeRecords==0")
            return null
        }
        if (data[0].timestamp < dateUtil.now() - 7 * 60 * 1000L && !allowOldData) {
            aapsLogger.debug(LTag.GLUCOSE, "oldData")
            return null
        }
        val now = data[0]
        val nowDate = now.timestamp
        var change: Double
        if (sizeRecords == 1) {
            aapsLogger.debug(LTag.GLUCOSE, "sizeRecords==1")
            return GlucoseStatusSMB(
                glucose = now.recalculated,
                noise = 0.0,
                delta = 0.0,
                shortAvgDelta = 0.0,
                longAvgDelta = 0.0,
                date = nowDate,
            ).asRounded()
        }
        val lastDeltas = ArrayList<Double>()
        val shortDeltas = ArrayList<Double>()
        val longDeltas = ArrayList<Double>()

        // Use the latest sgv value in the now calculations
        for (i in 1 until sizeRecords) {
            if (data[i].recalculated > 39) {
                val then = data[i]
                val thenDate = then.timestamp

                val minutesAgo = ((nowDate - thenDate) / (1000.0 * 60)).roundToLong()
                // multiply by 5 to get the same units as delta, i.e. mg/dL/5m
                change = now.recalculated - then.recalculated
                val avgDel = change / minutesAgo * 5
                aapsLogger.debug(LTag.GLUCOSE, "$then minutesAgo=$minutesAgo avgDelta=$avgDel")

                // use the average of all data points in the last 2.5m for all further "now" calculations
                // if (0 < minutesAgo && minutesAgo < 2.5) {
                //     // Keep and average all values within the last 2.5 minutes
                //     nowValueList.add(then.recalculated)
                //     now.value = average(nowValueList)
                //     // short_deltas are calculated from everything ~5-15 minutes ago
                // } else
                if (2.5 < minutesAgo && minutesAgo < 17.5) {
                    shortDeltas.add(avgDel)
                    // last_deltas are calculated from everything ~5 minutes ago
                    if (2.5 < minutesAgo && minutesAgo < 7.5) {
                        lastDeltas.add(avgDel)
                    }
                    // long_deltas are calculated from everything ~20-40 minutes ago
                } else if (17.5 < minutesAgo && minutesAgo < 42.5) {
                    longDeltas.add(avgDel)
                } else {
                    // Do not process any more records after >= 42.5 minutes
                    break
                }
            }
        }
        val shortAverageDelta = average(shortDeltas)
        val delta = if (lastDeltas.isEmpty()) {
            shortAverageDelta
        } else {
            average(lastDeltas)
        }

        // calculate 2 variables for 5% range; still using 5 minute data
        val bw = 0.05
        var sumBG: Double = now.recalculated
        var oldAvg: Double = sumBG
        var minutesDur = 0L
        var n = 1
        for (i in 1 until sizeRecords) {
            if (data[i].value > 39 && !data[i].filledGap) {
                n += 1
                val then = data[i]
                val thenDate: Long = then.timestamp
                //  stop the series if there was a CGM gap greater than 13 minutes, i.e. 2 regular readings
                //  needs shorter gap for Libre?
                if (((nowDate - thenDate) / (1000.0 * 60)).roundToInt() - minutesDur > 13) {
                    break
                }
                if (then.recalculated > oldAvg * (1 - bw) && then.recalculated < oldAvg * (1 + bw)) {
                    sumBG += then.recalculated
                    oldAvg = sumBG / n  // was: (i + 1)
                    minutesDur = ((nowDate - thenDate) / (1000.0 * 60)).roundToInt().toLong()
                } else {
                    break
                }
            }
        }

        return GlucoseStatusSMB(
            glucose = now.recalculated,
            date = nowDate,
            noise = 0.0, //for now set to nothing as not all CGMs report noise
            shortAvgDelta = shortAverageDelta,
            delta = delta,
            longAvgDelta = average(longDeltas),
        ).also { aapsLogger.debug(LTag.GLUCOSE, it.log(decimalFormatter)) }.asRounded()
    }

    companion object {

        fun average(array: ArrayList<Double>): Double {
            var sum = 0.0
            if (array.isEmpty()) return 0.0
            for (value in array) {
                sum += value
            }
            return sum / array.size
        }
    }
}