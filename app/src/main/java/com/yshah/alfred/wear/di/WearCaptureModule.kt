package com.yshah.alfred.wear.di

import android.content.Context
import com.yshah.alfred.wear.datalayer.DataLayerSender
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WearCaptureModule {
    @Provides
    @Singleton
    fun provideDataLayerSender(@ApplicationContext context: Context): DataLayerSender =
        DataLayerSender(context)
}
