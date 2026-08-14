package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.SalespersonDto
import com.tinhcd.myesalessfa.data.session.SessionStore
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.data.remote.PostgrestService
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supabase Auth is email-based, but reps have always typed a code like
 * "nvbh01". The mapping lives here so neither the UI nor :domain has to know
 * that a synthetic address is involved.
 */
private const val EMAIL_DOMAIN = "@esales.local"

/**
 * Sign-in, sign-out and the session Flow stay on the Supabase SDK — it owns token
 * refresh and session persistence, which is the part worth not hand-rolling. The
 * profile read below is a data call like any other and goes through Retrofit.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val service: PostgrestService,
    private val session: SessionStore,
) : AuthRepository {

    override val currentUser: Flow<Salesperson?> =
        client.auth.sessionStatus
            .map { status ->
                if (status is SessionStatus.Authenticated) loadProfileOrNull() else null
            }
            .onEach { session.current.value = it }

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
            session.current.value = profile
            DataResult.Success(profile)
        }
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun signOut(): DataResult<Unit> = try {
        client.auth.signOut()
        session.current.value = null
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    private suspend fun loadProfileOrNull(): Salesperson? = try {
        // RLS returns at most the signed-in rep's own row, so first() is the row.
        service.salesperson().firstOrNull()?.toDomain()
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
