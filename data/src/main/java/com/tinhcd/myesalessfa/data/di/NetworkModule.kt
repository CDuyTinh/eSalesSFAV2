package com.tinhcd.myesalessfa.data.di

import com.tinhcd.myesalessfa.data.BuildConfig
import com.tinhcd.myesalessfa.data.remote.PostgrestService
import com.tinhcd.myesalessfa.data.remote.http.SessionTokens
import com.tinhcd.myesalessfa.data.remote.http.SupabaseApiKey
import com.tinhcd.myesalessfa.data.remote.http.SupabaseAuthInterceptor
import com.tinhcd.myesalessfa.data.remote.http.SupabaseSessionTokens
import com.tinhcd.myesalessfa.data.remote.http.SupabaseTokenAuthenticator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Retrofit against PostgREST, which is where every data call goes.
 *
 * The Supabase SDK is still installed (see [SupabaseModule]) but only for auth.
 * This client borrows the rep's JWT from it per request, so there is one place
 * that owns the session and one place that talks to the database.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindings {
    @Binds
    @Singleton
    abstract fun bindSessionTokens(impl: SupabaseSessionTokens): SessionTokens
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideApiKey(): SupabaseApiKey {
        check(BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()) {
            "supabase.publishableKey is missing from local.properties"
        }
        return SupabaseApiKey(BuildConfig.SUPABASE_PUBLISHABLE_KEY)
    }

    /**
     * Lenient about unknown keys on purpose: the server may grow a column before
     * this build knows about it, and that must not break a rep mid-visit.
     * `explicitNulls = false` keeps nulls out of request bodies so PostgREST
     * applies its own column defaults instead of being handed a null.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttp(
        authInterceptor: SupabaseAuthInterceptor,
        authenticator: SupabaseTokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .authenticator(authenticator)
        .apply {
            if (BuildConfig.DEBUG) {
                // Headers only. Bodies carry order lines and stock figures, and
                // BASIC keeps the rep's JWT out of logcat too.
                addInterceptor(
                    HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                )
            }
        }
        // A rep on 2G inside a shop is the normal case, not the edge case.
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit {
        check(BuildConfig.SUPABASE_URL.isNotBlank()) {
            "supabase.url is missing from local.properties"
        }
        return Retrofit.Builder()
            .baseUrl(BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun providePostgrestService(retrofit: Retrofit): PostgrestService =
        retrofit.create(PostgrestService::class.java)
}
