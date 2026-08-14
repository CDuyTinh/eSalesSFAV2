package com.tinhcd.myesalessfa.data.remote.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.Response

private val json = Json { ignoreUnknownKeys = true }

/**
 * The error body both layers produce.
 *
 * A failed `raise exception` inside one of the database functions surfaces as
 * `code` P0001 with the message the function raised; the Edge Functions re-emit
 * that same message in the same field. So "1 of 1 lines could not be priced"
 * reaches the rep whether it was raised in plpgsql or in Deno.
 */
@Serializable
data class PostgrestErrorDto(
    val code: String? = null,
    val message: String? = null,
    val details: String? = null,
    val hint: String? = null,
)

/**
 * Carries the server's own message as the exception message, so the existing
 * `Exception.toAppError()` mapping keeps working unchanged.
 */
class PostgrestException(
    val status: Int,
    val code: String?,
    override val message: String,
) : Exception(message)

/**
 * Returns the decoded body of a successful write, or throws [PostgrestException].
 *
 * Writes go through `Response<T>` rather than a bare `T` so the error body is
 * still readable — Retrofit's own HttpException gives the status but discards the
 * message the function took the trouble to raise.
 */
fun <T> Response<T>.orThrow(): T {
    if (isSuccessful) {
        // A 2xx with no body is a contract mismatch, not a success worth passing on.
        return body() ?: throw PostgrestException(
            status = code(),
            code = null,
            message = "empty body from a ${code()} response",
        )
    }

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
