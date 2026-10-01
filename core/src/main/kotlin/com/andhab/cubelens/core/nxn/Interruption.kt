package com.andhab.cubelens.core.nxn

import java.util.concurrent.locks.ReentrantLock

/**
 * Cooperative cancellation for the long-running N×N computations (the 2×2 table, the 3-cycle
 * libraries and the big-cube search): throws [InterruptedException] if the current thread has been
 * interrupted, clearing its interrupted status as blocking JDK methods do. Work started with
 * `runInterruptible` in a coroutine therefore stops soon after the coroutine is cancelled.
 */
internal fun throwIfInterrupted() {
    if (Thread.interrupted()) throw InterruptedException("Interrupted while solving")
}

/**
 * A value built by the first thread that asks for it. Threads that ask while it is being built
 * wait for that build instead of starting their own.
 *
 * Unlike `lazy`, waiting can be interrupted, and a build that throws (for example because it
 * was interrupted, see [throwIfInterrupted]) is not cached: the next caller starts over.
 * Thread-safe.
 */
internal class BuildOnce<T : Any>(private val build: () -> T) {
    @Volatile
    private var value: T? = null
    private val lock = ReentrantLock()

    /** True once the value has been built. */
    val isBuilt: Boolean get() = value != null

    /**
     * The value, building it first if needed.
     *
     * @throws InterruptedException if the thread is interrupted while waiting for or running the build.
     */
    fun get(): T {
        value?.let { return it }
        lock.lockInterruptibly()
        try {
            return value ?: build().also { value = it }
        } finally {
            lock.unlock()
        }
    }
}
