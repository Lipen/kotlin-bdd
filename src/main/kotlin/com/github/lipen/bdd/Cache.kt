package com.github.lipen.bdd

internal interface ICache {
    val hits: Int
    val misses: Int

    fun clear()
}

enum class OpKind {
    And,
    Or,
    Xor,
    Ite,
    Size,
    Substitute,
    Exists,
    RelProduct,
}

@OptIn(ExperimentalUnsignedTypes::class)
internal class OpCache(
    bits: Int = 20,
) : ICache {
    init {
        require(bits in 0..31)
    }

    private val size = 1 shl bits
    private val bitmask = (size - 1).toULong()
    private val storage = ULongArray(size)
    private val data = IntArray(size)

    override var hits: Int = 0
        private set
    override var misses: Int = 0
        private set

    override fun clear() {
        storage.fill(0u)
    }

    private fun index(x: ULong): Int {
        return (x and bitmask).toInt()
    }

    private inline fun getOrCompute(compressed: ULong, init: () -> Int): Int {
        require(compressed != 0uL)

        val index = index(compressed)

        if (storage[index] != compressed) {
            misses++
            data[index] = init()
            storage[index] = compressed
        } else {
            hits++
        }

        return data[index]
    }

    private fun compressed(args: List<Int>): ULong {
        return pairing(args.map { it.toUInt().toULong() })
    }

    inline fun get(op: OpKind, args: List<Ref>, init: () -> Int): Int {
        val k = op.ordinal + 1
        val ix = args.map { it.index }
        val compressed = compressed(ix + k)
        return getOrCompute(compressed, init)
    }

    internal inline fun op(op: OpKind, args: List<Ref>, init: () -> Ref): Ref {
        val result = get(op, args) { init().index }
        return Ref(result)
    }
}

internal class MapCache<in K, V : Any>(
    val name: String,
    val map: MutableMap<in K, V> = mutableMapOf(), // WeakHashMap(),
) : ICache {
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
            misses++
            init().also { map[key] = it }
        } else {
            hits++
            v
        }
    }
}
