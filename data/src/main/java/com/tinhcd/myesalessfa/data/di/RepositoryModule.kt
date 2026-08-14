package com.tinhcd.myesalessfa.data.di

import com.tinhcd.myesalessfa.data.repository.AuthRepositoryImpl
import com.tinhcd.myesalessfa.data.repository.RouteRepositoryImpl
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
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
}
