package com.tinhcd.myesalessfa.data.remote.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.Response

private val json = Json { ignoreUnknownKeys = true }

/**
 * PostgREST's error body. A failed `raise exception` inside one of the RPCs
 * arrives here as `code` P0001 with the message the function raised, which is how
 * "1 of 1 lines could not be priced" reaches the rep's screen.
 */
@Serializable
data class PostgrestErrorDto(
    val code: String? = null,
    val message: String? = null,
    val details: String? = null,
    val hint: String? = null,
)

/**
 * Carries PostgREST's own message as the exception message, so the existing
 * `Exception.toAppError()` mapping keeps working unchanged after the move off
 * the Supabase SDK.
 */
class PostgrestException(
    val status: Int,
    val code: String?,
    override val message: String,
) : Exception(message)

/**
 * Turns a non-2xx write into a [PostgrestException].
 *
 * Writes are declared `Response<Unit>` because PostgREST returns an empty body
 * for them, and Retrofit would otherwise have nothing to decode. Reads decode
 * normally and Retrofit throws on failure by itself.
 */
fun Response<Unit>.orThrow() {
    if (isSuccessful) return

    val raw = errorBody()?.string().orEmpty()
    val parsed = runCatching { json.decodeFromString<PostgrestErrorDto>(raw) }.getOrNull()

    throw PostgrestException(
        status = code(),
        code = parsed?.code,
        // Falls back to the raw body: an error with no message at all is still
        // more debuggable than "HTTP 400".
        message = parsed?.message ?: raw.ifBlank { "HTTP ${code()} ${message()}" },
    )
}
