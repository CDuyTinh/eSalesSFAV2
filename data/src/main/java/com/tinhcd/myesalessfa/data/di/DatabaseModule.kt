package com.tinhcd.myesalessfa.data.di

import android.content.Context
import androidx.room.Room
import com.tinhcd.myesalessfa.data.local.AppDatabase
import com.tinhcd.myesalessfa.data.local.ConfigDao
import com.tinhcd.myesalessfa.data.local.OutboxDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "esales.db")
            // Only cache and outbox live here; on a schema change it is safer
            // to start clean than to carry a half-migrated queue. Revisit if
            // the outbox ever holds something not reproducible.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideOutboxDao(db: AppDatabase): OutboxDao = db.outboxDao()

    @Provides
    fun provideConfigDao(db: AppDatabase): ConfigDao = db.configDao()
}
