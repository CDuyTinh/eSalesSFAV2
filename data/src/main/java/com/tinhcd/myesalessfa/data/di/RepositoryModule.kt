package com.tinhcd.myesalessfa.data.di

import com.tinhcd.myesalessfa.data.repository.AuthRepositoryImpl
import com.tinhcd.myesalessfa.data.repository.CatalogRepositoryImpl
import com.tinhcd.myesalessfa.data.repository.CheckInRepositoryImpl
import com.tinhcd.myesalessfa.data.repository.DashboardRepositoryImpl
import com.tinhcd.myesalessfa.data.repository.DisplayAuditRepositoryImpl
import com.tinhcd.myesalessfa.data.repository.FeedbackRepositoryImpl
import com.tinhcd.myesalessfa.data.repository.ConfigRepositoryImpl
import com.tinhcd.myesalessfa.data.repository.OrderRepositoryImpl
import com.tinhcd.myesalessfa.data.repository.ReferenceDataSyncImpl
import com.tinhcd.myesalessfa.data.repository.RouteRepositoryImpl
import com.tinhcd.myesalessfa.data.repository.StockRepositoryImpl
import com.tinhcd.myesalessfa.data.repository.SurveyRepositoryImpl
import com.tinhcd.myesalessfa.data.repository.WorkflowRepositoryImpl
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.domain.repository.CatalogRepository
import com.tinhcd.myesalessfa.domain.repository.CheckInRepository
import com.tinhcd.myesalessfa.domain.repository.DashboardRepository
import com.tinhcd.myesalessfa.domain.repository.DisplayAuditRepository
import com.tinhcd.myesalessfa.domain.repository.FeedbackRepository
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.OrderRepository
import com.tinhcd.myesalessfa.domain.repository.ReferenceDataSync
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import com.tinhcd.myesalessfa.domain.repository.StockRepository
import com.tinhcd.myesalessfa.domain.repository.SurveyRepository
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The only place :app learns that these interfaces have Supabase-backed
 * implementations. Screens inject the interface.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindRouteRepository(impl: RouteRepositoryImpl): RouteRepository

    @Binds
    @Singleton
    abstract fun bindCheckInRepository(impl: CheckInRepositoryImpl): CheckInRepository

    @Binds
    @Singleton
    abstract fun bindConfigRepository(impl: ConfigRepositoryImpl): ConfigRepository

    @Binds
    @Singleton
    abstract fun bindDashboardRepository(impl: DashboardRepositoryImpl): DashboardRepository

    @Binds
    @Singleton
    abstract fun bindWorkflowRepository(impl: WorkflowRepositoryImpl): WorkflowRepository

    @Binds
    @Singleton
    abstract fun bindCatalogRepository(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds
    @Singleton
    abstract fun bindOrderRepository(impl: OrderRepositoryImpl): OrderRepository

    @Binds
    @Singleton
    abstract fun bindStockRepository(impl: StockRepositoryImpl): StockRepository

    @Binds
    @Singleton
    abstract fun bindDisplayAuditRepository(
        impl: DisplayAuditRepositoryImpl,
    ): DisplayAuditRepository

    @Binds
    @Singleton
    abstract fun bindSurveyRepository(impl: SurveyRepositoryImpl): SurveyRepository

    @Binds
    @Singleton
    abstract fun bindFeedbackRepository(impl: FeedbackRepositoryImpl): FeedbackRepository

    @Binds
    @Singleton
    abstract fun bindReferenceDataSync(impl: ReferenceDataSyncImpl): ReferenceDataSync
}
