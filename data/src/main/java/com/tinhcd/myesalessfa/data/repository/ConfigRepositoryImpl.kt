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
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
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
    private val client: SupabaseClient,
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

    override suspend fun refresh(): DataResult<Unit> = try {
        val settings = client.from("app_setting")
            .select(Columns.raw("key,value"))
            .decodeList<SettingDto>()

        val reasons = client.from("reason_code")
            .select(Columns.raw("id,code,name,kind")) {
                filter { eq("is_active", true) }
            }
            .decodeList<ReasonCodeDto>()

        val steps = client.from("sales_step")
            .select(Columns.raw("form_id,step,title_key,is_required,config")) {
                filter { eq("is_active", true) }
            }
            .decodeList<SalesStepDto>()

        val translations = client.from("translation")
            .select(Columns.raw("key,value")) {
                filter { eq("lang_code", activeLanguage()) }
            }
            .decodeList<TranslationDto>()

        dao.upsertSettings(settings.map { SettingEntity(it.key, it.value) })

        dao.clearReasons()
        dao.upsertReasons(reasons.map { ReasonEntity(it.id, it.code, it.name, it.kind) })

        // Replaced wholesale: a step removed upstream must disappear here too,
        // otherwise the rep keeps seeing a step head office retired.
        dao.clearSteps()
        dao.upsertSteps(
            steps.map { dto ->
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

        dao.upsertTranslations(translations.map { TranslationEntity(it.key, it.value) })

        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    /**
     * Labels come from the server, not strings.xml, exactly as in the legacy
     * app. Only the languages actually seeded are honoured.
     */
    private fun activeLanguage(): String {
        val device = Locale.getDefault().language.lowercase()
        return if (device in SUPPORTED_LANGUAGES) device else DEFAULT_LANGUAGE
    }

    private companion object {
        const val KEY_RADIUS = "gps_checkin_radius_m"
        const val KEY_ACCURACY = "gps_max_accuracy_m"
        const val KEY_ALLOW_REASON = "allow_reason_when_far"

        const val DEFAULT_LANGUAGE = "vi"
        val SUPPORTED_LANGUAGES = setOf("vi", "en")
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
