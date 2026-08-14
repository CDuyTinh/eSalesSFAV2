package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.local.ConfigDao
import com.tinhcd.myesalessfa.data.local.ReasonEntity
import com.tinhcd.myesalessfa.data.local.SalesStepEntity
import com.tinhcd.myesalessfa.data.local.SettingEntity
import com.tinhcd.myesalessfa.data.local.TranslationEntity
import com.tinhcd.myesalessfa.data.remote.ReasonCodeDto
import com.tinhcd.myesalessfa.data.remote.SalesStepDto
import com.tinhcd.myesalessfa.data.remote.SettingDto
import com.tinhcd.myesalessfa.data.remote.TranslationDto
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CheckInPolicy
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.SupportedSteps
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.data.remote.FunctionsService
import com.tinhcd.myesalessfa.data.remote.activeLanguage
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings, reason codes, workflow definition and labels are pulled once after
 * sign-in and cached, so the in-call screen renders instantly and the check-in
 * rules still apply when the shop turns out to be a signal dead spot.
 */
@Singleton
class ConfigRepositoryImpl @Inject constructor(
    private val service: FunctionsService,
    private val dao: ConfigDao,
) : ConfigRepository {

    override suspend fun checkInPolicy(): CheckInPolicy {
        val fallback = CheckInPolicy.Fallback
        return CheckInPolicy(
            defaultRadiusM = dao.setting(KEY_RADIUS)?.toIntOrNull() ?: fallback.defaultRadiusM,
            maxAccuracyM = dao.setting(KEY_ACCURACY)?.toIntOrNull() ?: fallback.maxAccuracyM,
            allowReasonWhenFar = dao.setting(KEY_ALLOW_REASON)?.toBooleanStrictOrNull()
                ?: fallback.allowReasonWhenFar,
        )
    }

    override suspend fun reasons(kind: ReasonKind): List<ReasonCode> =
        dao.reasons(kind.wireName()).map {
            ReasonCode(id = it.id, code = it.code, name = it.name, kind = kind)
        }

    /** Falls back to the key so a missing label is visible rather than blank. */
    override suspend fun translate(key: String): String = dao.translation(key) ?: key

    /**
     * Currently one rule, the legacy REQUIRE_STOCK_BEFORE_ORDER: count the
     * shelves before writing the order, so the order is based on what is actually
     * there.
     *
     * Absent defaults to off. A setting nobody configured must not stand between
     * a rep and a sale, and this is the one place where failing closed would cost
     * revenue rather than protect anything.
     */
    override suspend fun stepPrerequisites(): Map<String, String> = buildMap {
        if (dao.setting(KEY_STOCK_BEFORE_ORDER)?.toBooleanStrictOrNull() == true) {
            put(SupportedSteps.TAKE_ORDER, SupportedSteps.STOCK_OUTLET)
        }
    }

    /**
     * One call where there were four. Settings and translations arrive as maps
     * rather than row lists — they are only ever read by key, and the
     * list-to-map step used to happen here on every refresh.
     */
    override suspend fun refresh(): DataResult<Unit> = try {
        val bootstrap = service.bootstrap(lang = activeLanguage())

        dao.upsertSettings(bootstrap.settings.map { (key, value) -> SettingEntity(key, value) })

        dao.clearReasons()
        dao.upsertReasons(
            bootstrap.reasonCodes.map { ReasonEntity(it.id, it.code, it.name, it.kind) },
        )

        // Replaced wholesale: a step removed upstream must disappear here too,
        // otherwise the rep keeps seeing a step head office retired.
        dao.clearSteps()
        dao.upsertSteps(
            bootstrap.salesSteps.map { dto ->
                SalesStepEntity(
                    formId = dto.formId,
                    step = dto.step,
                    titleKey = dto.titleKey,
                    isRequired = dto.isRequired,
                    config = dto.config.entries.joinToString("\n") { (k, v) ->
                        "$k=${(v as? JsonPrimitive)?.content ?: v.toString()}"
                    },
                )
            },
        )

        dao.upsertTranslations(
            bootstrap.translations.map { (key, value) -> TranslationEntity(key, value) },
        )

        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    private companion object {
        const val KEY_RADIUS = "gps_checkin_radius_m"
        const val KEY_ACCURACY = "gps_max_accuracy_m"
        const val KEY_ALLOW_REASON = "allow_reason_when_far"
        const val KEY_STOCK_BEFORE_ORDER = "require_stock_before_order"
    }
}

/** Matches the Postgres `reason_kind` enum labels. */
internal fun ReasonKind.wireName(): String = when (this) {
    ReasonKind.NO_ORDER -> "no_order"
    ReasonKind.OUTLET_CLOSED -> "outlet_closed"
    ReasonKind.GPS_OUT_OF_RANGE -> "gps_out_of_range"
    ReasonKind.GPS_LOW_ACCURACY -> "gps_low_accuracy"
    ReasonKind.GPS_UNAVAILABLE -> "gps_unavailable"
    ReasonKind.PHOTO_SKIPPED -> "photo_skipped"
}
