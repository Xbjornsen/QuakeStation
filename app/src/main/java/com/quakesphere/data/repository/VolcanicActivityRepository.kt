package com.quakesphere.data.repository

import android.util.Xml
import com.quakesphere.data.api.VolcanicActivityApi
import com.quakesphere.data.db.VolcanoActivityDao
import com.quakesphere.data.db.VolcanoActivityEntity
import com.quakesphere.domain.model.VolcanoActivity
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.xmlpull.v1.XmlPullParser

/**
 * Fetches and caches the Smithsonian GVP Weekly Volcanic Activity Report.
 *
 * Responsibilities:
 *  - Pull the RSS feed (XML, ~30 entries)
 *  - Parse `<item>` elements with XmlPullParser
 *  - Upsert into Room
 *  - Prune entries that are older than the cutoff (default: 6 weeks; the
 *    feed itself only carries the *current* week's report, so kept history
 *    accumulates from successive sync runs)
 *
 * The DAO exposes Flow<List<VolcanoActivityEntity>> for the UI to observe;
 * we map entity → domain model here so callers never see Room types.
 */
@Singleton
class VolcanicActivityRepository @Inject constructor(
    private val api: VolcanicActivityApi,
    private val dao: VolcanoActivityDao
) {

    /** Currently-cached activity, most recent first. */
    fun observeAll(): Flow<List<VolcanoActivity>> =
        dao.getAll().map { rows -> rows.map { it.toDomain() } }

    /** Activity reported in the last [windowDays]. */
    fun observeRecent(windowDays: Int = 14): Flow<List<VolcanoActivity>> {
        val since = System.currentTimeMillis() - windowDays * 24L * 3600_000L
        return dao.getRecent(since).map { rows -> rows.map { it.toDomain() } }
    }

    /**
     * Fetch the latest weekly report and store any new entries. Idempotent —
     * existing entries (matched by GUID) are replaced. Returns the list of
     * NEW activity items not previously in cache, so the worker can decide
     * whether to fire a notification.
     */
    suspend fun sync(): List<VolcanoActivity> {
        val raw = try {
            api.getWeeklyReport().string()
        } catch (_: Throwable) {
            // Offline / 5xx — silent. UI keeps showing the cached state.
            return emptyList()
        }
        val parsed = parseRss(raw)
        if (parsed.isEmpty()) return emptyList()

        val now      = System.currentTimeMillis()
        val existing = dao.snapshot().map { it.id }.toHashSet()
        val newOnes  = parsed.filter { it.id !in existing }

        dao.upsertAll(parsed.map { it.toEntity(fetchedAt = now) })
        // Six-week rolling window — anything older is no longer "current
        // activity" by GVP standards.
        dao.pruneOlderThan(now - 42L * 24 * 3600_000L)
        return newOnes
    }

    // ── RSS parsing ─────────────────────────────────────────────────────────
    //
    // Feed shape (abridged):
    //   <rss><channel>
    //     <item>
    //       <title>Sangay, Ecuador</title>
    //       <description>Brief activity summary text...</description>
    //       <pubDate>Wed, 04 Jun 2025 00:00:00 GMT</pubDate>
    //       <link>https://volcano.si.edu/volcano.cfm?vn=352090</link>
    //       <guid>...</guid>
    //     </item>
    //     ...
    //   </channel></rss>
    //
    // We split <title> on the first comma into volcanoName + country.

    private fun parseRss(xml: String): List<VolcanoActivity> {
        val out = ArrayList<VolcanoActivity>(40)
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }

        var event = parser.eventType
        var inItem = false
        var title = ""; var desc = ""; var pubDate = ""; var link = ""; var guid = ""
        var currentTag: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name?.lowercase()
                    if (currentTag == "item") {
                        inItem = true; title = ""; desc = ""; pubDate = ""; link = ""; guid = ""
                    }
                }
                XmlPullParser.TEXT -> if (inItem) {
                    val text = parser.text ?: ""
                    when (currentTag) {
                        "title"       -> title   += text
                        "description" -> desc    += text
                        "pubdate"     -> pubDate += text
                        "link"        -> link    += text
                        "guid"        -> guid    += text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name?.lowercase() == "item" && inItem) {
                        inItem = false
                        val (name, country) = splitTitle(title.trim())
                        val publishedMillis = parsePubDate(pubDate.trim())
                        val id = guid.trim().ifBlank {
                            // Fall back to a stable composite key if the feed omits guid.
                            "${name}|${country}|${publishedMillis}"
                        }
                        if (name.isNotBlank()) {
                            out.add(
                                VolcanoActivity(
                                    id           = id,
                                    volcanoName  = name,
                                    country      = country,
                                    publishedAt  = publishedMillis,
                                    summary      = stripHtml(desc.trim()),
                                    link         = link.trim().ifBlank { null }
                                )
                            )
                        }
                    }
                    currentTag = null
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun splitTitle(title: String): Pair<String, String> {
        val idx = title.indexOf(',')
        if (idx < 0) return title to ""
        return title.substring(0, idx).trim() to title.substring(idx + 1).trim()
    }

    /**
     * RFC 822 date parser, e.g. "Wed, 04 Jun 2025 00:00:00 GMT".
     * Falls back to "now" on parse failure so the entry isn't silently dropped.
     */
    private fun parsePubDate(raw: String): Long {
        if (raw.isBlank()) return System.currentTimeMillis()
        return try {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            fmt.parse(raw)?.time ?: System.currentTimeMillis()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    /** Trivial tag stripper — feed sometimes wraps text in <p>…</p>. */
    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "")
         .replace("&nbsp;", " ")
         .replace("&amp;",  "&")
         .replace("&lt;",   "<")
         .replace("&gt;",   ">")
         .replace(Regex("\\s+"), " ")
         .trim()
}

private fun VolcanoActivityEntity.toDomain() = VolcanoActivity(
    id = id, volcanoName = volcanoName, country = country,
    publishedAt = publishedAt, summary = summary, link = link
)

private fun VolcanoActivity.toEntity(fetchedAt: Long) = VolcanoActivityEntity(
    id = id, volcanoName = volcanoName, country = country,
    publishedAt = publishedAt, summary = summary, link = link, fetchedAt = fetchedAt
)
