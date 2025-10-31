package com.eidolon.application.config

import com.minare.application.config.FrameConfiguration
import com.minare.application.config.FrameConfiguration.Companion.AutoSession

/**
 * Configuration for frame-based processing in Minare.
 *
 * This configuration defines the temporal structure of the system,
 * including frame duration, session intervals, and timing
 * parameters. Overridable by application.
 *
 */
class GameFrameConfiguration: FrameConfiguration() {
    /**
     *
     *     Frame settings
     *
     */

    /**
     * Duration of each frame in milliseconds.
     * This is the fundamental time unit for the system.
     *
     * Default: 100ms (10 frames per second)
     * Trade-off: Faster = faster response and more reliable ordering but
     *     more processor overhead
     */
    override val frameDurationMs: Long = 10000

    /**
     * How many frames ahead to prepare during normal operation.
     * Provides smooth operation without excessive pre-computation.
     *
     * Default: 2 frames
     * Balances low latency with operational buffer
     */
    override val normalOperationLookahead: Int = 100

    /**
     *
     *    Session settings
     *
     */

    /**
     * When to trigger an automatic new session. Options:
     * NEVER
     * FRAMES_PER_SESSION
     */
    override val autoSession: AutoSession = AutoSession.FRAMES_PER_SESSION

    /**
     * Number of frames between automatic sessions.
     * Sessions save snapshots, clear coordination memory
     * and reset frame number
     *
     * Default: 1000 frames
     * Trade-off: More frequent = faster recovery but more frequent pauses
     */
    override val framesPerSession: Long = 500

    /**
     *
     *    Timeline settings
     *
     */

    /**
     * Allow frame manifests to complete processing before hard pause
     */
    override val flushOperationsOnDetach: Boolean = true

    /**
     * Detach uses soft pause, buffering new input
     */
    override val bufferInputDuringDetach: Boolean = true

    /**
     * Replay uses soft pause, buffering new input
     */
    override val bufferInputDuringReplay: Boolean = true

    /**
     * Resume assigns operations from stale frames to new session,
     * preserving temporal order
     */
    override val assignOperationsOnResume: Boolean = false

    /**
     * Resume replays to current frameInProgress before returning play to State
     */
    override val replayOnResume: Boolean = false
}