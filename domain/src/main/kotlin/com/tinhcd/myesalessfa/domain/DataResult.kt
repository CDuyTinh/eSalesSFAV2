package com.tinhcd.myesalessfa.domain

/**
 * What every repository returns. Failures are values, not exceptions, so a
 * ViewModel cannot forget to handle one — the `when` will not compile.
 */
sealed interface DataResult<out T> {
    data class Success<T>(val data: T) : DataResult<T>
    data class Failure(val error: AppError) : DataResult<Nothing>
}

sealed interface AppError {
    /** No usable connection, or the request never reached the server. */
    data class Network(val cause: String? = null) : AppError

    /** Credentials rejected, or the session expired. */
    data class Auth(val message: String? = null) : AppError

    /** Server answered, but not with what we asked for. */
    data class Server(val code: Int? = null, val message: String? = null) : AppError

    /** The request was fine but the rules say no — e.g. checking in too far away. */
    data class Rule(val key: String, val message: String? = null) : AppError

    data class Unknown(val message: String? = null) : AppError
}

inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> = when (this) {
    is DataResult.Success -> DataResult.Success(transform(data))
    is DataResult.Failure -> this
}

fun <T> DataResult<T>.getOrNull(): T? = (this as? DataResult.Success)?.data
