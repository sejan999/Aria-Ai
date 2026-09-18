package com.aria.ai.di

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * One shared OkHttp client for REST + websockets and one Moshi instance.
 *
 * The interceptor logs only method, host, path and status. Query strings and
 * headers are never printed, so an API key can never reach logcat.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val TAG = "AriaAiHttp"

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val redactingLogger = Interceptor { chain ->
            val request = chain.request()
            val started = System.currentTimeMillis()
            val response = chain.proceed(request)
            Log.d(
                TAG,
                "${request.method} ${request.url.host}${request.url.encodedPath} -> " +
                    "${response.code} (${System.currentTimeMillis() - started}ms)"
            )
            response
        }

        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(redactingLogger)
            .build()
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
}