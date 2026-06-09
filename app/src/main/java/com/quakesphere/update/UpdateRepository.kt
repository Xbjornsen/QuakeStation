package com.quakesphere.update

import com.quakesphere.BuildConfig
import com.quakesphere.data.api.GitHubApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight in-app self-updater: checks the latest GitHub Release, compares
 * to the running version, and surfaces an [UpdateInfo] when there's something
 * newer.
 *
 * Versioning convention: release tags look like `v0.1.2`; the release pipeline
 * patches `versionName` in app/build.gradle.kts to match (sans the leading v).
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubApi: GitHubApi
) {

    suspend fun checkForUpdate(): UpdateInfo? {
        val release = try {
            gitHubApi.getLatestRelease(OWNER, REPO)
        } catch (_: Throwable) {
            // Offline / rate-limited / repo gone — silently fail. The user
            // can always check the GitHub page directly if they care.
            return null
        }

        val latest  = release.tagName.removePrefix("v").trim()
        val current = BuildConfig.VERSION_NAME.removePrefix("v").trim()
        if (compareSemver(latest, current) <= 0) return null

        // Prefer the APK ending with -release.apk or just the first .apk asset.
        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
            ?: return null

        return UpdateInfo(
            version       = latest,
            tag           = release.tagName,
            apkUrl        = apkAsset.browserDownloadUrl,
            apkSizeBytes  = apkAsset.sizeBytes,
            apkName       = apkAsset.name,
            notes         = release.body,
            releasePageUrl = release.htmlUrl
        )
    }

    /**
     * Compare two semver-ish strings like "0.1.2" and "0.1.10". Returns
     * negative / zero / positive in the standard compareTo convention.
     * Tolerates an optional `-suffix` (e.g. "0.2.0-beta1") by treating the
     * suffix as lower priority than no-suffix at the same numeric version.
     */
    internal fun compareSemver(a: String, b: String): Int {
        fun parts(v: String): Pair<List<Int>, String> {
            val dash = v.indexOf('-')
            val core = if (dash >= 0) v.substring(0, dash) else v
            val suffix = if (dash >= 0) v.substring(dash + 1) else ""
            val nums = core.split('.').map { it.toIntOrNull() ?: 0 }
            return Pair(nums, suffix)
        }
        val (aNums, aSuf) = parts(a)
        val (bNums, bSuf) = parts(b)
        val len = maxOf(aNums.size, bNums.size)
        for (i in 0 until len) {
            val x = aNums.getOrElse(i) { 0 }
            val y = bNums.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        // Equal core versions: no-suffix beats suffixed (1.0.0 > 1.0.0-rc1).
        return when {
            aSuf.isEmpty() && bSuf.isNotEmpty() ->  1
            aSuf.isNotEmpty() && bSuf.isEmpty() -> -1
            else -> aSuf.compareTo(bSuf)
        }
    }

    private companion object {
        const val OWNER = "Xbjornsen"
        // Repo renamed from "QuakeSphere" → "QuakeStation" mid-2026.
        // GitHub keeps the old name redirecting for a while, but baking
        // the new one in means future updates survive even after the
        // redirect expires.
        const val REPO  = "QuakeStation"
    }
}

data class UpdateInfo(
    /** Version string sans leading "v" (e.g. "0.1.3"). */
    val version: String,
    /** Original tag including the leading "v" (e.g. "v0.1.3"). */
    val tag: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkName: String,
    val notes: String?,
    val releasePageUrl: String
)
