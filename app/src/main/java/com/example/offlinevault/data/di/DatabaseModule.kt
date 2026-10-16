package com.example.offlinevault.data.di

import android.content.Context
import com.example.offlinevault.data.VaultDatabaseFactory
import com.example.offlinevault.security.SqlCipherKeyDeriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt bindings for the SQLCipher-backed Room database. */
@Module
@InstallIn(SingletonComponent::class)
public object DatabaseModule {
    @Provides
    @Singleton
    public fun provideVaultDatabaseFactory(
        @ApplicationContext context: Context,
        keyDeriver: SqlCipherKeyDeriver,
    ): VaultDatabaseFactory = VaultDatabaseFactory(context, keyDeriver)
}
