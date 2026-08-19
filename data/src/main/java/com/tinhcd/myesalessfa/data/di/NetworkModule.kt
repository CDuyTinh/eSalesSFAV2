package com.tinhcd.myesalessfa.data.di

import com.tinhcd.myesalessfa.data.BuildConfig
import com.tinhcd.myesalessfa.data.remote.service.BootstrapService
import com.tinhcd.myesalessfa.data.remote.service.CatalogueService
import com.tinhcd.myesalessfa.data.remote.service.CustomerRegistrationService
import com.tinhcd.myesalessfa.data.remote.service.DailyTargetService
import com.tinhcd.myesalessfa.data.remote.service.DashboardService
import com.tinhcd.myesalessfa.data.remote.service.DisplayAuditService
import com.tinhcd.myesalessfa.data.remote.service.FeedbackService
import com.tinhcd.myesalessfa.data.remote.service.FocusProductService
import com.tinhcd.myesalessfa.data.remote.service.LeaveService
import com.tinhcd.myesalessfa.data.remote.service.OrderService
import com.tinhcd.myesalessfa.data.remote.service.ReceivableService
import com.tinhcd.myesalessfa.data.remote.service.ReportService
import com.tinhcd.myesalessfa.data.remote.service.RouteService
import com.tinhcd.myesalessfa.data.remote.service.SiteStockService
import com.tinhcd.myesalessfa.data.remote.service.StockService
import com.tinhcd.myesalessfa.data.remote.service.SurveyService
import com.tinhcd.myesalessfa.data.remote.service.TimekeepingService
import com.tinhcd.myesalessfa.data.remote.service.VisitService
import com.tinhcd.myesalessfa.data.remote.service.WorkNoteService
import com.tinhcd.myesalessfa.data.remote.service.WorkflowService
import com.tinhcd.myesalessfa.data.remote.http.ClockSkewRetryInterceptor
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
 * Retrofit against the project's Edge Functions, which is where every data call
 * goes.
 *
 * The Supabase SDK is still installed (see [SupabaseModule]) but only for auth.
 * This client borrows the rep's JWT from it per request — the functions then build
 * their own database client from that same JWT, so RLS applies across the hop.
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
        clockSkewRetry: ClockSkewRetryInterceptor,
        authenticator: SupabaseTokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        // After the auth interceptor, so the retry carries the same token — the
        // token is not the problem, the clock is.
        .addInterceptor(clockSkewRetry)
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
            .baseUrl(BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    // One Retrofit, one proxy per service. Each is a thin interface over the same
    // client, so splitting them costs nothing at runtime and lets a repository ask
    // for only the calls it makes.

    @Provides
    @Singleton
    fun provideBootstrapService(retrofit: Retrofit): BootstrapService =
        retrofit.create(BootstrapService::class.java)

    @Provides
    @Singleton
    fun provideCatalogueService(retrofit: Retrofit): CatalogueService =
        retrofit.create(CatalogueService::class.java)

    @Provides
    @Singleton
    fun provideDashboardService(retrofit: Retrofit): DashboardService =
        retrofit.create(DashboardService::class.java)

    @Provides
    @Singleton
    fun provideRouteService(retrofit: Retrofit): RouteService =
        retrofit.create(RouteService::class.java)

    @Provides
    @Singleton
    fun provideVisitService(retrofit: Retrofit): VisitService =
        retrofit.create(VisitService::class.java)

    @Provides
    @Singleton
    fun provideTimekeepingService(retrofit: Retrofit): TimekeepingService =
        retrofit.create(TimekeepingService::class.java)

    @Provides
    @Singleton
    fun provideCustomerRegistrationService(retrofit: Retrofit): CustomerRegistrationService =
        retrofit.create(CustomerRegistrationService::class.java)

    @Provides
    @Singleton
    fun provideReportService(retrofit: Retrofit): ReportService =
        retrofit.create(ReportService::class.java)

    @Provides
    @Singleton
    fun provideReceivableService(retrofit: Retrofit): ReceivableService =
        retrofit.create(ReceivableService::class.java)

    @Provides
    @Singleton
    fun provideDailyTargetService(retrofit: Retrofit): DailyTargetService =
        retrofit.create(DailyTargetService::class.java)

    @Provides
    @Singleton
    fun provideFocusProductService(retrofit: Retrofit): FocusProductService =
        retrofit.create(FocusProductService::class.java)

    @Provides
    @Singleton
    fun provideSiteStockService(retrofit: Retrofit): SiteStockService =
        retrofit.create(SiteStockService::class.java)

    @Provides
    @Singleton
    fun provideWorkNoteService(retrofit: Retrofit): WorkNoteService =
        retrofit.create(WorkNoteService::class.java)

    @Provides
    @Singleton
    fun provideLeaveService(retrofit: Retrofit): LeaveService =
        retrofit.create(LeaveService::class.java)

    @Provides
    @Singleton
    fun provideWorkflowService(retrofit: Retrofit): WorkflowService =
        retrofit.create(WorkflowService::class.java)

    @Provides
    @Singleton
    fun provideStockService(retrofit: Retrofit): StockService =
        retrofit.create(StockService::class.java)

    @Provides
    @Singleton
    fun provideOrderService(retrofit: Retrofit): OrderService =
        retrofit.create(OrderService::class.java)

    @Provides
    @Singleton
    fun provideDisplayAuditService(retrofit: Retrofit): DisplayAuditService =
        retrofit.create(DisplayAuditService::class.java)

    @Provides
    @Singleton
    fun provideSurveyService(retrofit: Retrofit): SurveyService =
        retrofit.create(SurveyService::class.java)

    @Provides
    @Singleton
    fun provideFeedbackService(retrofit: Retrofit): FeedbackService =
        retrofit.create(FeedbackService::class.java)
}
