package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.local.ConfigDao
import com.tinhcd.myesalessfa.data.local.ReasonEntity
import com.tinhcd.myesalessfa.data.local.SettingEntity
import com.tinhcd.myesalessfa.data.remote.ReasonCodeDto
import com.tinhcd.myesalessfa.data.remote.SettingDto
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CheckInPolicy
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings and reason codes are pulled once after sign-in and cached, so the
 * check-in rules still apply when the shop turns out to be a signal dead spot.
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

    override suspend fun refresh(): DataResult<Unit> = try {
        val settings = client.from("app_setting")
            .select(Columns.raw("key,value"))
            .decodeList<SettingDto>()

        val reasons = client.from("reason_code")
            .select(Columns.raw("id,code,name,kind")) {
                filter { eq("is_active", true) }
            }
            .decodeList<ReasonCodeDto>()

        dao.upsertSettings(settings.map { SettingEntity(it.key, it.value) })
        dao.clearReasons()
        dao.upsertReasons(
            reasons.map { ReasonEntity(it.id, it.code, it.name, it.kind) },
        )
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    private companion object {
        const val KEY_RADIUS = "gps_checkin_radius_m"
        const val KEY_ACCURACY = "gps_max_accuracy_m"
        const val KEY_ALLOW_REASON = "allow_reason_when_far"
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
