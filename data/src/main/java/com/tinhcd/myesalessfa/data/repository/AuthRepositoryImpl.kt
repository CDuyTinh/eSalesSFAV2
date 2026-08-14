package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.SalespersonDto
import com.tinhcd.myesalessfa.data.session.SessionStore
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.data.remote.FunctionsService
import com.tinhcd.myesalessfa.data.remote.activeLanguage
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
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

/** No salesperson row points at this auth user. Permanent; head office must fix it. */
const val ERROR_NOT_PROVISIONED = "account_not_provisioned"

/** Signed in, but the profile could not be fetched. Transient; retrying may work. */
const val ERROR_PROFILE_UNAVAILABLE = "profile_unavailable"

/**
 * Sign-in, sign-out and the session Flow stay on the Supabase SDK — it owns token
 * refresh and session persistence, which is the part worth not hand-rolling. The
 * profile read below is a data call like any other and goes through Retrofit.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val service: FunctionsService,
    private val session: SessionStore,
) : AuthRepository {

    /**
     * Emits only once the SDK has settled on an answer.
     *
     * `sessionStatus` starts at [SessionStatus.Initializing] while the stored session is
     * being restored. Mapping that to null — as this did — means the first value a
     * collector sees says "signed out" before anyone has looked, and a caller taking
     * `first()` acts on it: a rep with a perfectly good session was sent back to the
     * login screen on every cold start. Filtering it out makes the flow's contract
     * "signed in as X, or definitely not signed in", which is what callers assume.
     */
    override val currentUser: Flow<Salesperson?> =
        client.auth.sessionStatus
            .filter { it !is SessionStatus.Initializing }
            .map { status ->
                if (status is SessionStatus.Authenticated) loadProfileOrNull() else null
            }
            .onEach { session.current.value = it }

    /**
     * Signing in has two steps that fail for unrelated reasons, and they are kept
     * apart on purpose.
     *
     * The credentials are checked first. Then the profile is fetched, and that fetch
     * can fail on its own — no connection, a rejected token, a server error. It used
     * to go through `loadProfileOrNull()`, which swallows everything into a null, and
     * a null here reads as "no salesperson row exists": a dropped request told the rep
     * their account was not provisioned and sent them to ring head office about a
     * problem that was neither theirs nor permanent. Observed on a real device, so the
     * two are now separate outcomes.
     */
    override suspend fun signIn(username: String, password: String): DataResult<Salesperson> {
        try {
            client.auth.signInWith(Email) {
                this.email = username.lowercase() + EMAIL_DOMAIN
                this.password = password
            }
        } catch (e: Exception) {
            return DataResult.Failure(e.toAppError())
        }

        val profile = try {
            service.bootstrap(lang = activeLanguage()).salesperson?.toDomain()
        } catch (e: Exception) {
            // The session is valid — the password was accepted — so it stays. The rep
            // can retry without typing it again, and the message says what actually
            // went wrong.
            return DataResult.Failure(AppError.Auth(ERROR_PROFILE_UNAVAILABLE))
        }

        if (profile == null) {
            // Authenticated, but genuinely no salesperson row points at this auth
            // user. Treat as a failed login rather than dropping the rep into an app
            // with no branch and no route.
            client.auth.signOut()
            return DataResult.Failure(AppError.Auth(ERROR_NOT_PROVISIONED))
        }

        session.current.value = profile
        return DataResult.Success(profile)
    }

    override suspend fun signOut(): DataResult<Unit> = try {
        client.auth.signOut()
        session.current.value = null
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    /**
     * Read from /bootstrap, which also carries settings, reason codes and labels.
     * Slightly more than is needed for a name in the app bar, but it is one small
     * request on session change only, and it avoids a second endpoint existing
     * purely to return one row. The catalogue is a separate call and not pulled
     * here.
     */
    private suspend fun loadProfileOrNull(): Salesperson? = try {
        service.bootstrap(lang = activeLanguage()).salesperson?.toDomain()
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
