package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.SalespersonDto
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supabase Auth is email-based, but reps have always typed a code like
 * "nvbh01". The mapping lives here so neither the UI nor :domain has to know
 * that a synthetic address is involved.
 */
private const val EMAIL_DOMAIN = "@esales.local"

private const val SALESPERSON_COLUMNS =
    "id,code,full_name,branch_id,branch:branch_id(id,code,name)"

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
) : AuthRepository {

    override val currentUser: Flow<Salesperson?> =
        client.auth.sessionStatus.map { status ->
            if (status is SessionStatus.Authenticated) loadProfileOrNull() else null
        }

    override suspend fun signIn(username: String, password: String): DataResult<Salesperson> = try {
        client.auth.signInWith(Email) {
            this.email = username.lowercase() + EMAIL_DOMAIN
            this.password = password
        }
        val profile = loadProfileOrNull()
        if (profile == null) {
            // Authenticated, but no salesperson row points at this auth user.
            // Treat as a failed login rather than dropping the user into an
            // app with no branch and no route.
            client.auth.signOut()
            DataResult.Failure(AppError.Auth("account_not_provisioned"))
        } else {
            DataResult.Success(profile)
        }
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun signOut(): DataResult<Unit> = try {
        client.auth.signOut()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    private suspend fun loadProfileOrNull(): Salesperson? = try {
        client.from("salesperson")
            .select(Columns.raw(SALESPERSON_COLUMNS))
            .decodeSingleOrNull<SalespersonDto>()
            ?.toDomain()
    } catch (e: Exception) {
        null
    }
}

private fun SalespersonDto.toDomain() = Salesperson(
    id = id,
    code = code,
    fullName = fullName,
    branchId = branchId,
    branchCode = branch?.code.orEmpty(),
    branchName = branch?.name.orEmpty(),
)

internal fun Exception.toAppError(): AppError {
    val text = message.orEmpty()
    return when {
        text.contains("Invalid login", ignoreCase = true) ||
            text.contains("invalid_credentials", ignoreCase = true) ->
            AppError.Auth("invalid_credentials")

        this is java.io.IOException -> AppError.Network(text)
        else -> AppError.Unknown(text)
    }
}
