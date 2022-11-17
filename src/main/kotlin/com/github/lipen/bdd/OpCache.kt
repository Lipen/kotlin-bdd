package com.github.lipen.bdd

enum class OpKind {
    And,
    Or,
    Xor,
    Ite,
    Size,
    Substitute,
}

@OptIn(ExperimentalUnsignedTypes::class)
internal class OpCache(
    bits: Int = 20,
) : Cache {
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

    internal inline fun get(args: List<Int>, init: () -> Int): Int {
        val compressed = compressed(args)
        return getOrCompute(compressed, init)
    }

    inline fun get(op: OpKind, args: List<Ref>, init: () -> Int): Int {
        val intArgs = args.map { it.index }
        val k = op.ordinal + 1
        return get(intArgs + k, init)
    }

    inline fun op(op: OpKind, args: List<Ref>, init: () -> Ref): Ref {
        val result = get(op, args) { init().index }
        return Ref(result)
    }
}
