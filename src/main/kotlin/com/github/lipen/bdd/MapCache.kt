package com.github.lipen.bdd

// private val logger = mu.KotlinLogging.logger {}

internal class MapCache<in K, V : Any>(
    val name: String,
    val map: MutableMap<in K, V> = mutableMapOf(), // WeakHashMap(),
) : Cache {
    override var hits: Int = 0
        private set
    override var misses: Int = 0
        private set

    override fun clear() {
        map.clear()
    }

    inline fun getOrCompute(key: K, init: () -> V): V {
        val v = map[key]
        return if (v == null) {
            // logger.debug { "cache miss for '$name' on $key" }
            misses++
            init().also { map[key] = it }
        } else {
            // logger.debug { "cache hit for '$name' on $key" }
            hits++
            v
        }
    }
}
