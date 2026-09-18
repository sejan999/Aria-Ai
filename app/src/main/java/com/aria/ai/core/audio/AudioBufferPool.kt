package com.aria.ai.core.audio

import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recycling pool for PCM-16 blocks.
 *
 * The recorder produces a frame several times per second and the player consumes
 * equally many, so allocating a fresh `ShortArray` per frame would hand the GC a
 * steady stream of short-lived garbage — on a voice-first app that is exactly the
 * kind of pressure that causes audible dropouts. Blocks are therefore borrowed
 * with [acquire] and handed back with [release].
 *
 * The pool is bounded both by block count and total samples, and every released
 * block is zeroed so a stale frame can never leak into a new utterance.
 */
@Singleton
class AudioBufferPool @Inject constructor() {

    private val lock = Any()
    private val free = ArrayDeque<ShortArray>()
    private var pooledSamples = 0

    /** Borrows a block of exactly [size] samples (a new one when none is free). */
    fun acquire(size: Int): ShortArray {
        require(size > 0) { "Block size must be positive" }
        synchronized(lock) {
            val iterator = free.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.size == size) {
                    iterator.remove()
                    pooledSamples -= candidate.size
                    return candidate
                }
            }
        }
        return ShortArray(size)
    }

    /** Borrows a block and guarantees it contains silence. */
    fun acquireZeroed(size: Int): ShortArray = acquire(size).also { Arrays.fill(it, 0) }

    /** Returns a block to the pool; oversized or null blocks are simply dropped. */
    fun release(buffer: ShortArray?) {
        if (buffer == null || buffer.isEmpty()) return
        synchronized(lock) {
            if (free.size >= MAX_POOLED_BLOCKS) return
            if (pooledSamples + buffer.size > MAX_POOLED_SAMPLES) return
            Arrays.fill(buffer, 0)
            free.addLast(buffer)
            pooledSamples += buffer.size
        }
    }

    /** Drops every pooled block (used when the voice session ends). */
    fun clear() {
        synchronized(lock) {
            free.clear()
            pooledSamples = 0
        }
    }

    fun pooledBlocks(): Int = synchronized(lock) { free.size }

    fun pooledSamples(): Int = synchronized(lock) { pooledSamples }

    private companion object {
        const val MAX_POOLED_BLOCKS = 24

        /** ≈ 30 seconds of 16 kHz mono audio. */
        const val MAX_POOLED_SAMPLES = 480_000
    }
}