package com.vibecaster.data

/**
 * A tiny personal recommender — no server, no tracking.
 *
 * Idea: the user's recent YouTube songs are the "seeds"; for every seed we
 * take YouTube's own related videos (a free collaborative-filtering signal),
 * then apply our own ranking:
 *   score = how many seeds surfaced it (overlap = strong signal)
 *         + how high it sat in each related list (position score)
 *         + a boost for the user's favourite artists (affinity from recents)
 * Songs the user already played/downloaded are dropped (novelty).
 *
 * Pure :core logic — Android and desktop share this exact code.
 */
object Recommender {

    suspend fun recommend(
        recents: List<Track>,
        downloads: List<Track>,
        limit: Int = 20,
        maxSeeds: Int = 3,
        relatedFetcher: suspend (String) -> List<Track>,
    ): List<Track> {
        val seeds = recents.filter { it.fromYouTube && it.sourceUrl != null }.take(maxSeeds)
        if (seeds.isEmpty()) return emptyList()

        val known = buildSet {
            recents.forEach { add(it.matchKey()) }
            downloads.forEach { add(it.matchKey()) }
        }
        val artistAffinity = recents.groupingBy { it.artist.lowercase() }.eachCount()

        val scores = HashMap<String, Double>()
        val byKey = HashMap<String, Track>()

        for (seed in seeds) {
            val rel = runCatching { relatedFetcher(seed.sourceUrl!!) }.getOrElse { emptyList() }
            if (rel.isEmpty()) continue
            rel.forEachIndexed { idx, t ->
                val key = t.matchKey()
                if (key in known) return@forEachIndexed
                if (t.durationMs > 15 * 60_000L) return@forEachIndexed // no podcasts/mixes
                byKey[key] = t
                val positionScore = (rel.size - idx).toDouble() / rel.size
                val artistBoost = (artistAffinity[t.artist.lowercase()] ?: 0) * 0.5
                scores[key] = (scores[key] ?: 0.0) + 1.0 + positionScore + artistBoost
            }
        }
        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { byKey[it.key] }
    }
}
