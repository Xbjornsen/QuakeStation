package com.quakesphere.domain.model

/**
 * One entry from the Smithsonian GVP Weekly Volcanic Activity Report.
 *
 * Domain model — the data layer maps the raw RSS XML into this via
 * VolcanicActivityRepository, and the UI layer reads it via a Flow.
 *
 * "Activity" here is broader than "eruption": new eruptions, ongoing
 * activity, ash plumes, lava flows, persistent unrest. The publishedAt
 * field is the date the weekly report itself was issued (always a
 * Wednesday), not the date activity started.
 */
data class VolcanoActivity(
    /** RFC-822 GUID from the feed — stable across weeks, used as primary key. */
    val id: String,
    /** Volcano name as the GVP writes it (e.g. "Sangay", "Kīlauea"). */
    val volcanoName: String,
    /** Country / region as the GVP writes it (e.g. "Ecuador", "Hawaiian Is"). */
    val country: String,
    /** Issue date of the weekly report this entry appeared in (epoch millis). */
    val publishedAt: Long,
    /** Free-text summary of the week's activity. May contain plain English sentences. */
    val summary: String,
    /** Link to the volcano's GVP page if present in the feed. */
    val link: String?
)
