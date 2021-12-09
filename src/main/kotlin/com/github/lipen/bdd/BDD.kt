package com.github.lipen.bdd

import kotlin.math.absoluteValue
import kotlin.math.min

private val logger = mu.KotlinLogging.logger {}

/**
 * [Cantor pairing function](https://en.wikipedia.org/wiki/Pairing_function#Cantor_pairing_function)
 */
private fun hash2(a: Int, b: Int): Int {
    require(a >= 0)
    require(b >= 0)
    return (a + 1) * (a + b + 1) / 2 + a
}

private fun hash3(a: Int, b: Int, c: Int): Int {
    require(a >= 0)
    require(b >= 0)
    require(c >= 0)
    return hash2(hash2(a, b), c)
}

class BDD {
    fun realSize(): Int = (1..size).count { storage[it] != null }

    var size: Int = 2
        private set
    private var capacity: Int = 1 shl 22
    private val storage: Array<Node?> = arrayOfNulls(capacity)
    private val buckets: IntArray = IntArray(capacity)
    private val nextTable: IntArray = IntArray(capacity)

    private val iteCache = Cache<Triple<Int, Int, Int>, Int>("ITE")
    private val andCache = Cache<Pair<Int, Int>, Int>("AND")
    private val orCache = Cache<Pair<Int, Int>, Int>("OR")
    private val xorCache = Cache<Pair<Int, Int>, Int>("XOR")
    private val caches = arrayOf(iteCache, andCache, orCache, xorCache)
    val cacheHits: Int
        get() = caches.sumOf { it.hits }
    val cacheMisses: Int
        get() = caches.sumOf { it.misses }

    val terminal: Node = Node(0, 0, 0)

    // val terminal: Node = Node(1, 0, 0, 0)
    val one: Int = 1
    val zero: Int = -1

    init {
        storage[1] = terminal
        buckets[0] = 1
    }

    data class Node(
        val v: Int,
        val low: Int,
        val high: Int,
    ) {
        // override fun equals(other: Any?): Boolean {
        //     if (this === other) return true
        //     if (javaClass != other?.javaClass) return false
        //
        //     other as Node
        //
        //     if (v != other.v) return false
        //     if (low != other.low) return false
        //     if (high != other.high) return false
        //
        //     return true
        // }
        //
        // override fun hashCode(): Int {
        //     val bitmask = capacity - 1
        //     return hash3(v, low.absoluteValue, high.absoluteValue) and bitmask
        // }
        //
        // override fun toString(): String {
        //     return "Node(v = $v, low = $low, high = $high)"
        // }
    }

    fun getNode(index: Int): Node? {
        return storage[index.absoluteValue]
    }

    fun terminal(value: Boolean): Int {
        return if (value) one else zero
    }

    fun variable(v: Int): Int {
        require(v != 0)
        if (v < 0) return -variable(-v)
        return mkNode(v = v, low = zero, high = one)
    }

    fun isZero(index: Int): Boolean {
        return index == -1
    }

    fun isOne(index: Int): Boolean {
        return index == 1
    }

    fun isTerminal(index: Int): Boolean {
        return isZero(index) || isOne(index)
    }

    @JvmName("_isZero")
    private fun Int.isZero(): Boolean = isZero(this)

    @JvmName("_isOne")
    private fun Int.isOne(): Boolean = isOne(this)

    @JvmName("_isTerminal")
    private fun Int.isTerminal(): Boolean = isTerminal(this)

    private fun getFreeIndex(): Int {
        // return size++
        for (i in 2 until size) {
            if (storage[i] == null) {
                return i
            }
        }
        return size++
    }

    private fun addNode(v: Int, low: Int, high: Int): Int {
        // val index = size++
        val index = getFreeIndex()
        storage[index] = Node(v, low, high)
        // storage[index] = Node(index, v, low, high)
        return index
    }

    private fun lookup(v: Int, low: Int, high: Int): Int {
        val bitmask = capacity - 1
        return hash3(v, low.absoluteValue, high.absoluteValue) and bitmask
    }

    fun mkNode(v: Int, low: Int, high: Int): Int {
        // require(v > 0)
        // require(low != 0)
        // require(high != 0)

        // Handle canonicity
        if (high < 0) {
            logger.debug { "mk: restoring canonicity" }
            return -mkNode(v, -low, -high)
        }

        // Handle duplicates
        if (low == high) {
            logger.debug { "mk: duplicates" }
            return low
        }

        val bucketIndex = lookup(v, low, high)
        logger.debug { "mk: bucketIndex for ($v, $low, $high) is $bucketIndex" }
        var index = buckets[bucketIndex]

        if (index == 0) {
            // Create new node
            return addNode(v, low, high).also { buckets[bucketIndex] = it }
                .also { logger.debug { "mk: created new node @$it" } }
        }

        while (true) {
            val node = storage[index]
                ?: error("NPE @$index")
            if (node.v == v && node.low == low && node.high == high) {
                // The node already exists
                logger.debug { "mk: node @$index $node already exists" }
                return index
            }
            val next = nextTable[index]
            if (next == 0) {
                // Create new node and add it to the bucket
                return addNode(v, low, high).also { nextTable[index] = it }
                    .also { logger.debug { "mk: created new node @$it" } }
            } else {
                // Go to the next node in the bucket
                logger.debug { "mk: iterating over the bucket to $next" }
                index = next
            }
        }
    }

    fun collectGarbage(roots: List<Int>) {
        logger.debug { "Collecting garbage..." }

        caches.forEach { it.map.clear() }

        val used = mutableSetOf(1)
        for (root in roots) {
            _descendants(root, used)
        }
        logger.debug { "Alive: ${used.sorted()}" }

        // val ndead = size - used.size
        // val dead = ArrayDeque<Int>(ndead)
        // for (i in 1..size) {
        //     if (i !in used) {
        //         dead.add(i)
        //     }
        // }

        for (i in buckets.indices) {
            var index = buckets[i]

            if (index != 0) {
                logger.debug { "Cleaning bucket #$i pointing to @$index ${getNode(index)}..." }

                while (index != 0 && index !in used) {
                    logger.debug { "(pre) Dropping @$index ${getNode(index)}, next = ${nextTable[index]}" }
                    storage[index] = null
                    val next = nextTable[index]
                    nextTable[index] = 0
                    index = next
                }

                logger.debug { "Relinking bucket #$i to @$index ${getNode(index)}, next = ${nextTable[index]}" }
                buckets[i] = index

                var prev = index
                if (index != 0) {
                    index = nextTable[index]
                }

                while (index != 0) {
                    val next = nextTable[index]
                    if (index !in used) {
                        logger.debug { "(after) Dropping @$index ${getNode(index)}, next = ${nextTable[index]}" }
                        storage[index] = null
                        nextTable[index] = 0
                        logger.debug { "(after) Relinking next(@$prev) to @$next" }
                        nextTable[prev] = next
                    } else {
                        logger.debug { "(after) Not dropping @$index ${getNode(index)}, next = ${nextTable[index]}" }
                        prev = index
                    }
                    index = next
                }
            }
        }
    }

    fun topCofactors(index: Int, v: Int): Pair<Int, Int> {
        // index - node
        // v - variable
        require(v > 0)

        if (isTerminal(index)) {
            return Pair(index, index)
        }
        val node = getNode(index)!!
        if (v < node.v) {
            return Pair(index, index)
        }
        check(v == node.v)
        return if (index < 0) {
            Pair(-node.low, -node.high)
        } else {
            Pair(node.low, node.high)
        }
    }

    @JvmName("_topCofactors")
    private fun Int.topCofactors(v: Int): Pair<Int, Int> = topCofactors(this, v)

    private fun _apply(u: Int, v: Int, f: (Int, Int) -> Int): Int {
        logger.debug { "_apply(u = $u, v = $v)" }

        require(!isTerminal(u))
        require(!isTerminal(v))

        val a = getNode(u)!!
        val b = getNode(v)!!

        val i = a.v
        val j = b.v
        val m = min(i, j)
        logger.debug { "_apply(@$u, @$v): min variable = $m" }

        // cofactors of u,v
        val (u0, u1) = u.topCofactors(m)
        logger.debug { "_apply(@$u, @$v): cofactors of u = $u:" }
        logger.debug { "    u0 = @$u0 (${getNode(u0)})" }
        logger.debug { "    u1 = @$u1 (${getNode(u1)})" }
        val (v0, v1) = v.topCofactors(m)
        logger.debug { "_apply(@$u, @$v): cofactors of v = $v:" }
        logger.debug { "    v0 = @$v0 (${getNode(v0)})" }
        logger.debug { "    v1 = @$v1 (${getNode(v1)})" }

        // cofactors of the resulting node w
        val w0 = f(u0, v0)
        val w1 = f(u1, v1)
        logger.debug { "_apply(@$u, @$v): cofactors of w:" }
        logger.debug { "    w0 = @$w0 (${getNode(w0)})" }
        logger.debug { "    w1 = @$w1 (${getNode(w1)})" }

        return mkNode(v = m, low = w0, high = w1).also {
            logger.debug { "_apply(@$u, @$v): w = $it (${getNode(it)})" }
        }
    }

    fun applyAnd(u: Int, v: Int): Int {
        logger.debug { "applyAnd(u = $u, v = $v)" }

        if (u.isZero() || v.isZero()) {
            logger.debug { "applyAnd(@$u, @$v): either u or v is Zero" }
            return zero
        }
        if (u.isOne()) {
            logger.debug { "applyAnd(@$u, @$v): u is One" }
            return v
        }
        if (v.isOne()) {
            logger.debug { "applyAnd(@$u, @$v): v is One" }
            return u
        }
        if (u == v) {
            logger.debug { "applyAnd(@$u, @$v): u == v" }
            return u
        }
        if (u == -v) {
            logger.debug { "applyAnd(@$u, @$v): u == ~v" }
            return zero
        }

        return andCache.getOrCompute(Pair(u, v)) {
            _apply(u, v, ::applyAnd)
        }
    }

    fun applyOr(u: Int, v: Int): Int {
        logger.debug { "applyOr(u = $u, v = $v)" }

        if (u.isOne() || v.isOne()) {
            logger.debug { "applyOr(@$u, @$v): either u or v is One" }
            return one
        }
        if (u.isZero()) {
            logger.debug { "applyOr(@$u, @$v): u is Zero" }
            return v
        }
        if (v.isZero()) {
            logger.debug { "applyOr(@$u, @$v): v is Zero" }
            return u
        }
        if (u == v) {
            logger.debug { "applyOr(@$u, @$v): u == v" }
            return u
        }
        if (u == -v) {
            logger.debug { "applyOr(@$u, @$v): u == ~v" }
            return one
        }

        return orCache.getOrCompute(Pair(u, v)) {
            _apply(u, v, ::applyOr)
        }
    }

    private fun _descendants(index: Int, visited: MutableSet<Int>) {
        val r = index.absoluteValue
        if (visited.add(r)) {
            logger.debug { "visited $index" }
            val node = storage[r]!!
            _descendants(node.low, visited)
            _descendants(node.high, visited)
        }
    }

    fun descendants(index: Int): Set<Int> {
        val visited = mutableSetOf(1)
        _descendants(index, visited)
        return visited
    }

    private fun _exists(index: Int, v: Int, cache: Cache<Int, Int>): Int {
        logger.debug { "_exists(@$index, $v)" }
        if (index.isTerminal()) {
            return index
        }
        return cache.getOrCompute(index) {
            val node = getNode(index)!!
            val low = node.low.let { if (index < 0) -it else it }
            val high = node.high.let { if (index < 0) -it else it }
            when {
                v < node.v -> index
                v > node.v -> {
                    val r0 = _exists(low, v, cache)
                    val r1 = _exists(high, v, cache)
                    logger.debug { "_exists(@$index, $v): cofactors of $node by $v:" }
                    logger.debug { "  r0 = $r0 (${getNode(r0)}" }
                    logger.debug { "  r1 = $r1 (${getNode(r1)}" }
                    mkNode(v = node.v, low = r0, high = r1)
                }
                else -> applyOr(low, high)
            }
        }.also {
            logger.debug { "_exists(@$index, $v) = @$it (${getNode(it)})" }
        }
    }

    fun exists(index: Int, v: Int): Int {
        logger.debug { "exists(@$index, $v)" }
        require(v > 0)
        val cache = Cache<Int, Int>("EXISTS($v)")
        return _exists(index, v, cache)
    }
}

fun main() {
    val bdd = BDD()
    val one = bdd.one
    val zero = bdd.zero

    val x1 = bdd.variable(1)
    val x2 = bdd.variable(2)
    val x3 = bdd.variable(3)
    // println("-".repeat(42))
    // val f = bdd.applyAnd(-x1, x2)
    // println("-".repeat(42))
    // val g = bdd.exists(f, 2)

    println("-".repeat(42))
    val g = bdd.applyOr(x1, -x2)
    println("-".repeat(42))
    val c1 = bdd.applyAnd(bdd.applyAnd(-x1, -x2), x3)
    val c2 = bdd.applyAnd(x1, -x3)
    val c3 = bdd.applyAnd(x1, x2)
    println("-".repeat(42))
    val f = bdd.applyOr(bdd.applyOr(c1, c2), c3)
    println("-".repeat(42))
    val e = bdd.exists(f, 3)

    println("-".repeat(42))
    println("f = @$f = ${bdd.getNode(f)}")
    println("e = @$e = ${bdd.getNode(e)}")
    println("g = @$g = ${bdd.getNode(g)}")

    println("-".repeat(42))
    for (i in 1 until bdd.size) {
        val node = bdd.getNode(i)
        println("@$i = $node")
    }

    // println("-".repeat(42))
    // println("Collecting garbage...")
    // bdd.collectGarbage(listOf(f,e,g))
    //
    // println("-".repeat(42))
    // for (i in 1 until bdd.size) {
    //     val node = bdd.getNode(i)
    //     println("@$i = $node")
    // }
}
