package com.tinhcd.myesalessfa.data.di

import android.content.Context
import androidx.room.Room
import com.tinhcd.myesalessfa.data.local.AppDatabase
import com.tinhcd.myesalessfa.data.local.CatalogDao
import com.tinhcd.myesalessfa.data.local.ConfigDao
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
            // Everything in here is reference data pulled from the server on
            // launch, so losing it costs one refresh and nothing else.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideConfigDao(db: AppDatabase): ConfigDao = db.configDao()

    @Provides
    fun provideCatalogDao(db: AppDatabase): CatalogDao = db.catalogDao()
}
