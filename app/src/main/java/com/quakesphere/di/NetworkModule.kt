package com.quakesphere.di

import com.quakesphere.data.api.EarthquakeApiService
import com.quakesphere.data.api.GitHubApi
import com.quakesphere.data.api.VolcanicActivityApi
import javax.inject.Named
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_URL = "https://earthquake.usgs.gov/fdsnws/event/1/"
    private const val TIMEOUT_SECONDS = 30L

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(loggingInterceptor: HttpLoggingInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("usgs")
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideEarthquakeApiService(@Named("usgs") retrofit: Retrofit): EarthquakeApiService {
        return retrofit.create(EarthquakeApiService::class.java)
    }

    /** Separate Retrofit for GitHub Releases — different base URL than USGS. */
    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideGitHubApi(@Named("github") retrofit: Retrofit): GitHubApi =
        retrofit.create(GitHubApi::class.java)

    /** Smithsonian GVP Weekly Volcanic Activity Report (RSS). */
    @Provides
    @Singleton
    @Named("smithsonian")
    fun provideSmithsonianRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://volcano.si.edu/news/")
            .client(okHttpClient)
            // No converter — we read the raw ResponseBody and parse XML manually.
            // GsonConverterFactory would still happily handle other endpoints if
            // we added them here later, but for the RSS feed we don't want it
            // intercepting.
            .build()
    }

    @Provides
    @Singleton
    fun provideVolcanicActivityApi(
        @Named("smithsonian") retrofit: Retrofit
    ): VolcanicActivityApi = retrofit.create(VolcanicActivityApi::class.java)
}
