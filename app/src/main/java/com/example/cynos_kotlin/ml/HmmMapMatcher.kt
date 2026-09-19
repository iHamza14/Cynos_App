package com.example.cynos_kotlin.ml

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Online HMM / Viterbi map matcher.
 *
 * Hidden state  = "which road segment am I actually on, and where along it"
 * Observation   = the dead-reckoning position (noisy, drifting)
 *
 * This is the *forward* pass of Viterbi only. A batch matcher would run the
 * whole track then backtrack to find the single best path; we can't do that
 * live, so each step we keep the accumulated best score for every candidate
 * and emit the current argmax. In practice that is what every real-time map
 * matcher does, and it recovers from a wrong guess within a few steps because
 * the bad candidate's score stops growing.
 *
 * Scoring (all in log space):
 *   emission   = -0.5 * (perpendicular_distance / SIGMA)^2
 *                  -> being far from a road is unlikely
 *   transition = -|route_distance - dr_displacement| / BETA
 *                  -> the distance you moved along the roads should match how
 *                     far DR says you moved
 *              + DISCONNECT_PENALTY if the two segments don't share a node
 *                  -> you can't teleport between unconnected roads
 */
class HmmMapMatcher(
    private val graph: RoadGraph
) {

    companion object {
        /** DR position noise, metres. Larger than GPS because DR drifts. */
        private const val SIGMA = 20.0

        /** Transition slack, metres. */
        private const val BETA = 12.0

        /** How far to look for candidate roads, metres. */
        private const val SEARCH_RADIUS = 60.0

        /** Log-penalty for jumping between roads that don't touch. */
        private const val DISCONNECT_PENALTY = -3.0

        /** Keep at most this many candidates per step (cheap pruning). */
        private const val MAX_CANDIDATES = 12

        private const val TAG = "DR"
    }

    class Match(
        val segIndex: Int,
        val x: Double,
        val y: Double,
        val lat: Double,
        val lon: Double,
        val offsetM: Double,
        val roadName: String?
    )

    private var prevCands: List<Projection> = emptyList()
    private var prevScores: DoubleArray = DoubleArray(0)
    private var prevX = 0.0
    private var prevY = 0.0
    private var hasPrev = false

    /**
     * Feed one dead-reckoning fix, get the snapped position back.
     *
     * @return null if there is no road within SEARCH_RADIUS (off-network:
     *         a car park, a field, or the graph hasn't loaded here yet).
     */
    fun update(lat: Double, lon: Double): Match? {
        val px = graph.toX(lon)
        val py = graph.toY(lat)

        // ---- candidate generation ----
        val near = graph.nearbySegments(px, py, SEARCH_RADIUS)
        if (near.isEmpty()) {
            reset()
            return null
        }

        var cands = near
            .map { graph.project(px, py, it) }
            .filter { it.dist <= SEARCH_RADIUS }
            .sortedBy { it.dist }

        if (cands.isEmpty()) {
            reset()
            return null
        }
        if (cands.size > MAX_CANDIDATES) cands = cands.subList(0, MAX_CANDIDATES)

        // ---- emission ----
        val scores = DoubleArray(cands.size)
        for (i in cands.indices) {
            val d = cands[i].dist / SIGMA
            scores[i] = -0.5 * d * d
        }

        // ---- transition (skipped on the very first fix) ----
        if (hasPrev && prevCands.isNotEmpty()) {
            val drStep = hypot(px - prevX, py - prevY)

            for (i in cands.indices) {
                var best = Double.NEGATIVE_INFINITY
                for (j in prevCands.indices) {
                    val pj = prevCands[j]
                    val routeDist = hypot(cands[i].x - pj.x, cands[i].y - pj.y)

                    var t = prevScores[j] - abs(routeDist - drStep) / BETA
                    if (!graph.connected(pj.segIndex, cands[i].segIndex)) {
                        t += DISCONNECT_PENALTY
                    }
                    if (t > best) best = t
                }
                scores[i] += best
            }
        }

        // ---- normalise so scores don't run off to -infinity over a long drive ----
        val maxScore = scores.max()
        for (i in scores.indices) scores[i] -= maxScore

        prevCands = cands
        prevScores = scores
        prevX = px
        prevY = py
        hasPrev = true

        // ---- emit current best ----
        var bestIdx = 0
        for (i in scores.indices) if (scores[i] > scores[bestIdx]) bestIdx = i
        val c = cands[bestIdx]

        return Match(
            segIndex = c.segIndex,
            x = c.x,
            y = c.y,
            lat = graph.toLat(c.y),
            lon = graph.toLon(c.x),
            offsetM = c.dist,
            roadName = graph.segments[c.segIndex].name
        )
    }

    fun reset() {
        prevCands = emptyList()
        prevScores = DoubleArray(0)
        hasPrev = false
    }
}