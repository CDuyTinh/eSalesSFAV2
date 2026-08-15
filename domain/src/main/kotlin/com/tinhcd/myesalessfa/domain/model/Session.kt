package com.tinhcd.myesalessfa.domain.model

/**
 * Whether there is a session, kept deliberately separate from whether the rep's
 * profile could be loaded.
 *
 * These used to be the same thing: the session was exposed as a nullable
 * [Salesperson], and a failed profile fetch produced the same null as having no
 * session at all. So a single dropped request at launch read as "signed out" and
 * sent a rep with a perfectly good session back to the login screen — observed on
 * a device, and the reason this type exists.
 *
 * The distinction is not cosmetic. A session with no profile is a real state the
 * app can be in and has to say something honest about: the rep is authenticated
 * and can read their route, but writes stamp `salesperson_id` and `branch_id` from
 * the profile, so a check-in cannot go ahead until it arrives.
 */
sealed interface SessionState {

    /** No valid session. The login screen is the only correct destination. */
    data object SignedOut : SessionState

    /**
     * A valid session. [rep] is null when the profile has not been fetched yet —
     * transient, and recoverable with `AuthRepository.refreshProfile()`.
     *
     * A missing profile is never "not provisioned": that answer arrives from the
     * server as a successful response with no salesperson, and it resolves to
     * [SignedOut] instead, because an app with no branch and no route is worse than
     * a login screen that says why.
     */
    data class SignedIn(val rep: Salesperson?) : SessionState {
        /** True while the session is usable for reading but not for writing. */
        val profileMissing: Boolean get() = rep == null
    }
}
