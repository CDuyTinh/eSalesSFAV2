package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.BootstrapDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Every data call the app makes is an Edge Function, split here by the feature it
 * serves rather than gathered into one interface. A repository then injects only
 * the calls it actually makes, and a change to the order endpoints cannot ripple
 * into anything that never touches orders.
 *
 * The functions run outside Postgres but build their database client from the
 * caller's own JWT, so RLS still scopes everything exactly as it did when the app
 * spoke to PostgREST. Verified: nvbh02 calling /route gets an empty route rather
 * than nvbh01's customers.
 *
 * Auth is not among them. Sign-in, session persistence and token refresh stay with
 * the Supabase SDK; these services borrow the JWT it owns.
 */
interface BootstrapService {

    /**
     * Profile, settings, reason codes, workflow definition and labels in one
     * response. Called after sign-in, before the first check-in can need any of it.
     */
    /**
     * Returns [Response] rather than the body so a failure keeps the message the
     * function raised. Retrofit's own HttpException reports the status and throws
     * the body away, which is how a bootstrap failure reached a rep as nothing
     * more useful than "could not load".
     */
    @GET("bootstrap")
    suspend fun bootstrap(@Query("lang") lang: String): Response<BootstrapDto>
}
