package com.tinhcd.myesalessfa.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * A scope that lives as long as the process, for work that belongs to the app
 * rather than to whatever screen happens to be open.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    /**
     * Exists so session state can be resolved once and shared, rather than
     * recomputed per collector.
     *
     * The session used to be a cold flow that fetched the rep's profile on every
     * collection, and two screens collect it — a single sign-in fired `/bootstrap`
     * three times over. That was merely wasteful while the profile was read-only;
     * it becomes wrong as soon as the profile can be retried, because "retry" has
     * to mean one attempt whose outcome everybody sees, not one attempt per
     * listener.
     *
     * [SupervisorJob] so one failed child cannot take the session collector down
     * with it, and no cancellation: the owner is the process.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
