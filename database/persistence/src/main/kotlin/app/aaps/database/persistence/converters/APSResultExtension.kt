package app.aaps.database.persistence.converters

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.RT
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.json.Json
import javax.inject.Provider

// An aborted algorithm run keeps its non-finite inputs for diagnosis, and recording them must not fail the loop run.
private val json = Json { allowSpecialFloatingPointValues = true }

fun app.aaps.database.entities.APSResult.fromDb(apsResultProvider: Provider<APSResult>): APSResult =
    when (algorithm) {
        app.aaps.database.entities.APSResult.Algorithm.AMA,
        app.aaps.database.entities.APSResult.Algorithm.SMB      ->
            apsResultProvider.get().with(json.decodeFromString(this.resultJson)).also { result ->
                result.date = this.timestamp
                result.glucoseStatus = try {
                    this.glucoseStatusJson?.let { json.decodeFromString(it) }
                } catch (_: Exception) {
                    null
                }
                result.currentTemp = this.currentTempJson?.let { json.decodeFromString(it) }
                result.iobData = this.iobDataJson?.let { json.decodeFromString(it) }
                result.oapsProfile = this.profileJson?.let { json.decodeFromString(it) }
                result.mealData = this.mealDataJson?.let { json.decodeFromString(it) }
                result.autosensResult = this.autosensDataJson?.let { json.decodeFromString(it) }
            }

        app.aaps.database.entities.APSResult.Algorithm.AUTO_ISF ->
            apsResultProvider.get().with(json.decodeFromString(this.resultJson)).also { result ->
                result.date = this.timestamp
                result.glucoseStatus = try {
                    this.glucoseStatusJson?.let { json.decodeFromString(it) }
                } catch (_: Exception) {
                    null
                }
                result.currentTemp = this.currentTempJson?.let { json.decodeFromString(it) }
                result.iobData = this.iobDataJson?.let { json.decodeFromString(it) }
                result.oapsProfileAutoIsf = this.profileJson?.let { json.decodeFromString(it) }
                result.mealData = this.mealDataJson?.let { json.decodeFromString(it) }
                result.autosensResult = this.autosensDataJson?.let { json.decodeFromString(it) }
            }

        else                                                    -> error("Unsupported")
    }

@OptIn(ExperimentalSerializationApi::class)
fun APSResult.toDb(): app.aaps.database.entities.APSResult =
    when (algorithm) {
        APSResult.Algorithm.AMA,
        APSResult.Algorithm.SMB      ->
            app.aaps.database.entities.APSResult(
                timestamp = this.date,
                algorithm = this.algorithm.toDb(),
                glucoseStatusJson = this.glucoseStatus?.let { json.encodeToString(GlucoseStatusSMB.serializer(), it as GlucoseStatusSMB) },
                currentTempJson = this.currentTemp?.let { json.encodeToString(CurrentTemp.serializer(), it) },
                iobDataJson = this.iobData?.let { json.encodeToString(ArraySerializer(IobTotal.serializer()), it) },
                profileJson = this.oapsProfile?.let { json.encodeToString(OapsProfile.serializer(), it) },
                mealDataJson = this.mealData?.let { json.encodeToString(MealData.serializer(), it) },
                autosensDataJson = this.autosensResult?.let { json.encodeToString(AutosensResult.serializer(), it) },
                resultJson = json.encodeToString(RT.serializer(), this.rawData() as RT)
            )

        APSResult.Algorithm.AUTO_ISF ->
            app.aaps.database.entities.APSResult(
                timestamp = this.date,
                algorithm = this.algorithm.toDb(),
                glucoseStatusJson = this.glucoseStatus?.let { json.encodeToString(GlucoseStatusAutoIsf.serializer(), it as GlucoseStatusAutoIsf) },
                currentTempJson = this.currentTemp?.let { json.encodeToString(CurrentTemp.serializer(), it) },
                iobDataJson = this.iobData?.let { json.encodeToString(ArraySerializer(IobTotal.serializer()), it) },
                profileJson = this.oapsProfileAutoIsf?.let { json.encodeToString(OapsProfileAutoIsf.serializer(), it) },
                mealDataJson = this.mealData?.let { json.encodeToString(MealData.serializer(), it) },
                autosensDataJson = this.autosensResult?.let { json.encodeToString(AutosensResult.serializer(), it) },
                resultJson = json.encodeToString(RT.serializer(), this.rawData() as RT)
            )

        else                         -> error("Unsupported")
    }

fun app.aaps.database.entities.APSResult.Algorithm.fromDb(): APSResult.Algorithm =
    when (this) {
        app.aaps.database.entities.APSResult.Algorithm.AMA      -> APSResult.Algorithm.AMA
        app.aaps.database.entities.APSResult.Algorithm.SMB      -> APSResult.Algorithm.SMB
        app.aaps.database.entities.APSResult.Algorithm.AUTO_ISF -> APSResult.Algorithm.AUTO_ISF
        else                                                    -> error("Unsupported")
    }

fun APSResult.Algorithm.toDb(): app.aaps.database.entities.APSResult.Algorithm =
    when (this) {
        APSResult.Algorithm.AMA      -> app.aaps.database.entities.APSResult.Algorithm.AMA
        APSResult.Algorithm.SMB      -> app.aaps.database.entities.APSResult.Algorithm.SMB
        APSResult.Algorithm.AUTO_ISF -> app.aaps.database.entities.APSResult.Algorithm.AUTO_ISF
        else                         -> error("Unsupported")
    }