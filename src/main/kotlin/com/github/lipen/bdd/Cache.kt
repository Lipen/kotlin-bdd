package com.github.lipen.bdd

import java.util.WeakHashMap

private val logger = mu.KotlinLogging.logger {}

internal class Cache<K, V>(
    val name: String,
    val map: MutableMap<K, V> = WeakHashMap(),
) {
    var hits: Int = 0
        private set
    var misses: Int = 0
        private set

    fun getOrCompute(key: K, default: (K) -> V): V {
        return map.compute(key) { k, v ->
            if (v == null) {
                logger.debug { "cache miss for '$name' on $k" }
                misses++
                default(k)
            } else {
                logger.debug { "cache hit for '$name' on $k" }
                hits++
                v
            }
        }!!
    }
}
