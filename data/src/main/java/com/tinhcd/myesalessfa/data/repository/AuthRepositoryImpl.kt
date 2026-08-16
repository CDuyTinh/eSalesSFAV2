package com.tinhcd.myesalessfa.data.repository

import android.util.Log

import com.tinhcd.myesalessfa.data.di.ApplicationScope
import com.tinhcd.myesalessfa.data.remote.dto.SalespersonDto
import com.tinhcd.myesalessfa.data.session.SessionStore
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.model.SessionState
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.data.remote.service.BootstrapService
import com.tinhcd.myesalessfa.data.remote.dto.activeLanguage
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import com.tinhcd.myesalessfa.data.util.SingleFlight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private val service: BootstrapService,
    private val session: SessionStore,
    // `@param:` keeps the qualifier on the constructor parameter, which is where
    // Dagger looks, now that the scope is also held as a property.
    @param:ApplicationScope private val scope: CoroutineScope,
) : AuthRepository {

    /**
     * The one resolved answer, shared by everyone who asks.
     *
     * Null means "not resolved yet" and is filtered out rather than exposed: the
     * SDK reports [SessionStatus.Initializing] while it restores a stored session,
     * and publishing that as a state would put the ambiguity straight back — a
     * caller taking `first()` would act on "no session" before anyone had looked,
     * which is precisely the bug this replaced.
     */
    private val resolved = MutableStateFlow<SessionState?>(null)

    private val profileFlight = SingleFlight<DataResult<Salesperson>>(scope)

    init {
        // One collector for the process. The profile is fetched when the session
        // changes, not once per screen that happens to be watching.
        scope.launch {
            client.auth.sessionStatus
                .filter { it !is SessionStatus.Initializing }
                .collect { status ->
                    // The outcome is published by refreshProfile itself; there is
                    // nothing for this collector to decide, and no second copy of
                    // the three-outcome logic to keep in step with the first.
                    if (status is SessionStatus.Authenticated) {
                        refreshProfile()
                    } else {
                        publish(SessionState.SignedOut)
                    }
                }
        }
    }

    override val sessionState: Flow<SessionState> = resolved.filterNotNull()

    override val currentUser: Flow<Salesperson?> = sessionState
        .map { state -> (state as? SessionState.SignedIn)?.rep }
        .distinctUntilChanged()

    /**
     * Signing in has two steps that fail for unrelated reasons, and they are kept
     * apart on purpose.
     *
     * The credentials are checked first. Then the profile is fetched, and that fetch
     * can fail on its own — no connection, a rejected token, a server error. It used
     * to go through a helper that swallowed everything into a null, and a null read
     * as "no salesperson row exists": a dropped request told the rep their account
     * was not provisioned and sent them to ring head office about a problem that was
     * neither theirs nor permanent. Observed on a real device, so the two are now
     * separate outcomes.
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

        // The session collector resolves this sign-in too. Both land on the same
        // single-flight call, so the collector joins this one rather than issuing
        // a second — and the caller still gets the specific reason on failure.
        return refreshProfile()
    }

    /**
     * Fetches the profile for a session that already exists.
     *
     * Reached from three directions: the session collector above, straight after
     * sign-in, and a screen offering to retry once an earlier fetch failed. All
     * three want the same three outcomes, so they share one implementation rather
     * than drifting apart.
     *
     * Read from /bootstrap, which also carries settings, reason codes and labels.
     * Slightly more than is needed for a name in the app bar, but it is one small
     * request on session change only, and it avoids a second endpoint existing
     * purely to return one row. The catalogue is a separate call and not pulled here.
     *
     * Single-flight. Signing in reaches this twice at once — once from [signIn],
     * once from the collector reacting to the very same event — and on a real
     * device the two racing requests produced a 400 on one of them, which surfaced
     * to the rep as "signed in but no profile" on an otherwise good login. A second
     * caller now joins the request already in the air instead of starting another.
     *
     * Deliberately not a cache: the entry is cleared as soon as the call settles,
     * so a later retry after a failure really does go back to the server.
     */
    override suspend fun refreshProfile(): DataResult<Salesperson> =
        profileFlight.run { fetchProfile() }

    private suspend fun fetchProfile(): DataResult<Salesperson> {
        val profile = try {
            service.bootstrap(lang = activeLanguage()).orThrow().salesperson?.toDomain()
        } catch (e: Exception) {
            // Logged because the rep is shown a fixed sentence, and without this a
            // failed bootstrap left no record anywhere of what the server said.
            Log.w("AuthRepository", "bootstrap failed: ${e.message}", e)

            // The session is valid — it was accepted — so it stays, and the state
            // says signed in without a profile. The rep can retry without typing a
            // password again, and the message says what actually went wrong.
            publish(SessionState.SignedIn(null))
            return DataResult.Failure(AppError.Auth(ERROR_PROFILE_UNAVAILABLE))
        }

        if (profile == null) {
            // Authenticated, and the server answered plainly that no salesperson row
            // points at this auth user. Permanent, so treat it as a failed session
            // rather than dropping the rep into an app with no branch and no route.
            client.auth.signOut()
            publish(SessionState.SignedOut)
            return DataResult.Failure(AppError.Auth(ERROR_NOT_PROVISIONED))
        }

        publish(SessionState.SignedIn(profile))
        return DataResult.Success(profile)
    }

    override suspend fun signOut(): DataResult<Unit> = try {
        client.auth.signOut()
        publish(SessionState.SignedOut)
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    /**
     * [SessionStore] is what stamps salesperson_id and branch_id onto writes, so it
     * has to move in step with the state rather than be updated by whoever
     * remembers to.
     */
    private fun publish(next: SessionState) {
        resolved.value = next
        session.current.value = (next as? SessionState.SignedIn)?.rep
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
