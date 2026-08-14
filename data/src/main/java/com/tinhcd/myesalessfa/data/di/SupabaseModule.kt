package com.tinhcd.myesalessfa.data.di

import com.tinhcd.myesalessfa.data.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage
import javax.inject.Singleton

/**
 * The Supabase SDK, kept for what it is genuinely good at: sign-in, persisting the
 * session across app restarts, and refreshing the access token before it expires.
 *
 * Postgrest is deliberately not installed. Data calls go through Retrofit (see
 * [NetworkModule]), which borrows the JWT this client owns. Two HTTP stacks exist
 * as a result — Ktor underneath auth-kt, OkHttp under Retrofit — and that is the
 * accepted cost of not hand-rolling token refresh.
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient {
        check(BuildConfig.SUPABASE_URL.isNotBlank()) {
            "supabase.url is missing from local.properties"
        }
        check(BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()) {
            "supabase.publishableKey is missing from local.properties"
        }
        return createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ) {
            install(Auth)
            install(Storage)
        }
    }
}
