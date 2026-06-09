package com.quakesphere.data.api

import okhttp3.ResponseBody
import retrofit2.http.GET

/**
 * Smithsonian GVP Weekly Volcanic Activity Report RSS endpoint.
 *
 * We return [ResponseBody] (raw XML) and parse with Android's built-in
 * XmlPullParser inside the repository — adds no extra dependency, and the
 * feed is small (~30 entries / ~30 KB).
 *
 * Base URL is `https://volcano.si.edu/news/` (configured in NetworkModule
 * under @Named("smithsonian")). Feed updates every Wednesday.
 */
interface VolcanicActivityApi {
    @GET("WeeklyVolcanoRSS.xml")
    suspend fun getWeeklyReport(): ResponseBody
}
