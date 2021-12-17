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
    return hash2(hash2(a, b).absoluteValue, c).absoluteValue
}

@JvmInline
value class Ref(val index: Int) : Comparable<Ref> {
    val negated: Boolean
        get() = index < 0

    init {
        require(index != 0)
    }

    operator fun unaryMinus(): Ref {
        return Ref(-index)
    }

    override fun compareTo(other: Ref): Int {
        return index.compareTo(other.index)
    }

    override fun toString(): String {
        // return "@$index"
        return "${if (negated) "~" else ""}@${index.absoluteValue}"
    }
}

class BDD(
    val storageCapacity: Int = 1 shl 20, // 24 ~ 320MB, 25 ~ 640MB, 26 ~ 1G
    val bucketsCapacity: Int = storageCapacity,
) {
    private val buckets = IntArray(bucketsCapacity)
    internal val storage = Storage(storageCapacity)

    val size: Int get() = storage.lastIndex
    val realSize: Int get() = storage.realSize

    fun maxChain(): Int = chains().maxOrNull() ?: 0
    fun chains(): Sequence<Int> = buckets.asSequence().map { i ->
        var count = 0
        var j = i
        while (j != 0) {
            count++
            j = next(j)
        }
        count
    }

    private val iteCache = Cache<Triple<Int, Ref, Ref>, Ref>("ITE")
    private val andCache = Cache<Pair<Ref, Ref>, Ref>("AND")
    private val orCache = Cache<Pair<Ref, Ref>, Ref>("OR")
    private val xorCache = Cache<Pair<Ref, Ref>, Ref>("XOR")
    private val caches = arrayOf(iteCache, andCache, orCache, xorCache)
    val cacheHits: Int
        get() = caches.sumOf { it.hits }
    val cacheMisses: Int
        get() = caches.sumOf { it.misses }

    val andCacheMap = andCache.map
    val orCacheMap = orCache.map

    val one = Ref(1)
    val zero = Ref(-1)

    init {
        storage.alloc(1) // terminal node
        buckets[0] = 1
    }

    fun variable(i: Int): Int = storage.variable(i)
    fun variable(node: Ref): Int = variable(node.index.absoluteValue)

    fun low(i: Int): Ref = Ref(storage.low(i))
    fun low(node: Ref): Ref = low(node.index.absoluteValue)

    fun high(i: Int): Ref = Ref(storage.high(i))
    fun high(node: Ref): Ref = high(node.index.absoluteValue)

    fun next(i: Int): Int = storage.next(i)
    fun next(node: Ref): Int = next(node.index.absoluteValue)

    fun isZero(node: Ref): Boolean = node == zero
    fun isOne(node: Ref): Boolean = node == one
    fun isTerminal(node: Ref): Boolean = isZero(node) || isOne(node)

    private fun addNode(v: Int, low: Ref, high: Ref): Ref {
        val index = storage.add(v, low.index, high.index)
        return Ref(index)
    }

    private fun lookup(v: Int, low: Ref, high: Ref): Int {
        val bitmask = bucketsCapacity - 1
        return hash3(v, low.index.absoluteValue, high.index.absoluteValue) and bitmask
        // return hash3(v, low.absoluteValue, high.absoluteValue).mod(capacity2)
    }

    @Suppress("FoldInitializerAndIfToElvis")
    fun mkNode(v: Int, low: Ref, high: Ref): Ref {
        logger.debug { "mk(v = $v, low = $low, high = $high)" }

        require(v > 0)
        require(low.index != 0)
        require(high.index != 0)

        // Handle canonicity
        if (high.negated) {
            logger.debug { "mk: restoring canonicity" }
            return -mkNode(v = v, low = -low, high = -high)
        }

        // Handle duplicates
        if (low == high) {
            logger.debug { "mk: duplicates @$low == @$high" }
            return low
        }

        val bucketIndex = lookup(v, low, high)
        logger.debug { "mk: bucketIndex for ($v, $low, $high) is $bucketIndex" }
        var index = buckets[bucketIndex]

        if (index == 0) {
            // Create new node
            return addNode(v, low, high).also { buckets[bucketIndex] = it.index }
                .also { logger.debug { "mk: created new node @$it" } }
        }

        while (true) {
            check(index > 0)

            if (variable(index) == v && low(index) == low && high(index) == high) {
                // The node already exists
                logger.debug { "mk: node @$index already exists" }
                return Ref(index)
            }

            val next = next(index)

            if (next == 0) {
                // Create new node and add it to the bucket
                return addNode(v, low, high).also { storage.setNext(index, it.index) }
                    .also { logger.debug { "mk: created new node $it after $index" } }
            } else {
                // Go to the next node in the bucket
                logger.debug { "mk: iterating over the bucket to $next" }
                index = next
            }
        }
    }

    fun mkVar(v: Int): Ref {
        require(v != 0)
        return if (v < 0) {
            -mkNode(v = -v, low = zero, high = one)
        } else {
            mkNode(v = v, low = zero, high = one)
        }
    }

    fun collectGarbage(roots: List<Ref>) {
        logger.debug { "Collecting garbage..." }

        caches.forEach { it.map.clear() }

        val alive = mutableSetOf(1)
        for (root in roots) {
            _descendants(root, alive)
        }
        logger.debug { "Alive: ${alive.sorted()}" }

        for (i in buckets.indices) {
            var index = buckets[i]

            if (index != 0) {
                logger.debug { "Cleaning bucket #$i pointing to $index..." }

                while (index != 0 && index !in alive) {
                    val next = next(index)
                    logger.debug { "Dropping $index, next = $next" }
                    storage.drop(index)
                    index = next
                }

                logger.debug { "Relinking bucket #$i to $index, next = ${next(index)}" }
                buckets[i] = index

                var prev = index
                while (prev != 0) {
                    var curr = next(prev)
                    while (curr != 0) {
                        if (curr !in alive) {
                            val next = next(curr)
                            logger.debug { "Dropping $curr, prev = $prev, next = $next" }
                            storage.drop(curr)
                            curr = next
                        } else {
                            logger.debug { "Keeping $curr, prev = $prev}" }
                            break
                        }
                    }
                    if (next(prev) != curr) {
                        logger.debug { "Relinking next($prev) from ${next(prev)} to $curr" }
                        storage.setNext(prev, curr)
                    }
                    prev = curr
                }
            }
        }
    }

    fun topCofactors(node: Ref, v: Int): Pair<Ref, Ref> {
        require(v > 0)
        return when {
            isTerminal(node) -> Pair(node, node)
            v < variable(node) -> Pair(node, node)
            else -> {
                check(v == variable(node))
                if (node.negated) {
                    Pair(-low(node), -high(node))
                } else {
                    Pair(low(node), high(node))
                }
            }
        }
    }

    private inline fun _apply(u: Ref, v: Ref, f: (Ref, Ref) -> Ref): Ref {
        logger.debug { "_apply(u = $u, v = $v)" }

        require(!isTerminal(u))
        require(!isTerminal(v))

        val i = variable(u)
        val j = variable(v)
        val m = min(i, j)
        logger.debug { "_apply($u, $v): min variable = $m" }

        // cofactors of u
        val (u0, u1) = topCofactors(u, m)
        logger.debug { "_apply($u, $v): cofactors of u = $u:" }
        logger.debug { "    u0 = $u0 (${getTriplet(u0)})" }
        logger.debug { "    u1 = $u1 (${getTriplet(u1)})" }
        // cofactors of v
        val (v0, v1) = topCofactors(v, m)
        logger.debug { "_apply($u, $v): cofactors of v = $v:" }
        logger.debug { "    v0 = $v0 (${getTriplet(v0)})" }
        logger.debug { "    v1 = $v1 (${getTriplet(v1)})" }
        // cofactors of the resulting node w
        val w0 = f(u0, v0)
        val w1 = f(u1, v1)
        logger.debug { "_apply($u, $v): cofactors of w:" }
        logger.debug { "    w0 = $w0 (${getTriplet(w0)})" }
        logger.debug { "    w1 = $w1 (${getTriplet(w1)})" }

        return mkNode(v = m, low = w0, high = w1).also {
            logger.debug { "_apply($u, $v): w = $it (${getTriplet(it)})" }
        }
    }

    fun applyAnd(u: Ref, v: Ref): Ref {
        logger.debug { "applyAnd(u = $u, v = $v)" }

        if (isZero(u) || isZero(v)) {
            logger.debug { "applyAnd($u, $v): either u or v is Zero" }
            return zero
        }
        if (isOne(u)) {
            logger.debug { "applyAnd($u, $v): u is One" }
            return v
        }
        if (isOne(v)) {
            logger.debug { "applyAnd($u, $v): v is One" }
            return u
        }
        if (u == v) {
            logger.debug { "applyAnd($u, $v): u == v" }
            return u
        }
        if (u == -v) {
            logger.debug { "applyAnd($u, $v): u == ~v" }
            return zero
        }

        // val uVar = variable(u)
        // val vVar = variable(v)
        // if ((uVar > vVar) || (uVar == vVar && u.index > v.index)) {
        //     return andCache.getOrCompute(Pair(v, u)) {
        //         _apply(v, u, ::applyAnd)
        //     }
        // }

        return andCache.getOrCompute(Pair(u, v)) {
            _apply(u, v, ::applyAnd)
        }
    }

    fun applyOr(u: Ref, v: Ref): Ref {
        logger.debug { "applyOr(u = $u, v = $v)" }

        if (isOne(u) || isOne(v)) {
            logger.debug { "applyOr($u, $v): either u or v is One" }
            return one
        }
        if (isZero(u)) {
            logger.debug { "applyOr($u, $v): u is Zero" }
            return v
        }
        if (isZero(v)) {
            logger.debug { "applyOr($u, $v): v is Zero" }
            return u
        }
        if (u == v) {
            logger.debug { "applyOr($u, $v): u == v" }
            return u
        }
        if (u == -v) {
            logger.debug { "applyOr($u, $v): u == ~v" }
            return one
        }

        // val uVar = variable(u)
        // val vVar = variable(v)
        // if ((uVar > vVar) || (uVar == vVar && u.index > v.index)) {
        //     return orCache.getOrCompute(Pair(v, u)) {
        //         _apply(v, u, ::applyOr)
        //     }
        // }

        return orCache.getOrCompute(Pair(u, v)) {
            _apply(u, v, ::applyOr)
        }
    }

    private fun _descendants(node: Ref, visited: MutableSet<Int>) {
        val i = node.index.absoluteValue
        if (visited.add(i)) {
            _descendants(low(i), visited)
            _descendants(high(i), visited)
        }
    }

    fun descendants(node: Ref): Set<Int> {
        val visited = mutableSetOf(1)
        _descendants(node, visited)
        return visited
    }

    private fun _exists(node: Ref, j: Int, vars: Set<Int>, cache: Cache<Ref, Ref>): Ref {
        logger.debug { "_exists($node, $vars) ($node = ${getTriplet(node)})" }
        if (isTerminal(node)) {
            return node
        }
        return cache.getOrCompute(node) {
            val i = node.index.absoluteValue
            val v = variable(i)
            val low = low(i).let { if (node.negated) -it else it }
            val high = high(i).let { if (node.negated) -it else it }

            var m = j
            val sortedVars = vars.sorted()
            // skip non-essential variables
            while (m < vars.size) {
                if (sortedVars[m] < v) {
                    m++
                } else {
                    break
                }
            }
            // exhausted valuation
            if (m == vars.size) {
                return@getOrCompute node
            }

            val r0 = _exists(low, m, vars, cache)
            val r1 = _exists(high, m, vars, cache)

            if (v in vars) {
                applyOr(r0, r1)
            } else {
                mkNode(v = v, low = r0, high = r1)
            }

            // when {
            //     v < variable(i) -> node
            //     v > variable(i) -> {
            //         val r0 = _exists(low, v, cache)
            //         val r1 = _exists(high, v, cache)
            //         logger.debug { "_exists($node, $v): cofactors of $node (${getTriplet(node)}) by $v:" }
            //         logger.debug { "  r0 = $r0 (${getTriplet(r0)}" }
            //         logger.debug { "  r1 = $r1 (${getTriplet(r1)}" }
            //         mkNode(v = variable(i), low = r0, high = r1)
            //     }
            //     else -> applyOr(low, high)
            // }
        }.also {
            logger.debug { "_exists($node, $vars) = $it (${getTriplet(it)})" }
        }
    }

    fun exists(node: Ref, vars: Set<Int>): Ref {
        logger.debug { "exists($node, $vars)" }
        val cache = Cache<Ref, Ref>("EXISTS($vars)")
        return _exists(node, 0, vars, cache).also {
            logger.debug { "exists($node, $vars) = $it (${getTriplet(node)})" }
            logger.debug { "  cache ${cache.name}: hits=${cache.hits}, misses=${cache.misses}" }
        }
    }

    fun exists(node: Ref, v: Int): Ref {
        logger.debug { "exists($node, $v)" }
        return exists(node, setOf(v))
    }

    private fun _relProduct(
        f: Ref,
        g: Ref,
        vars: Set<Int>,
        cache: Cache<Triple<Ref, Ref, Set<Int>>, Ref>,
    ): Ref {
        if (isZero(f) || isZero(g)) {
            return zero
        }
        if (isOne(f) && isOne(g)) {
            return one
        }
        if (isOne(f)) {
            return exists(g, vars)
        }
        if (isOne(g)) {
            return exists(f, vars)
        }

        return cache.getOrCompute(Triple(f, g, vars)) {
            val i = variable(f)
            val j = variable(g)
            val m = min(i, j)

            val (f0, f1) = topCofactors(f, m)
            val (g0, g1) = topCofactors(g, m)
            val h0 = _relProduct(f0, g0, vars, cache)
            val h1 = _relProduct(f1, g1, vars, cache)

            if (m in vars) {
                applyOr(h0, h1)
            } else {
                mkNode(v = m, low = h0, high = h1)
            }
        }
    }

    fun relProduct(f: Ref, g: Ref, vars: Set<Int>): Ref {
        val relProductCache: Cache<Triple<Ref, Ref, Set<Int>>, Ref> = Cache("RELPROD($vars)")
        return _relProduct(f, g, vars, relProductCache)
    }

    private fun _oneSat(node: Ref, parity: Boolean, model: MutableList<Boolean?>): Boolean {
        if (isTerminal(node)) {
            return parity
        }
        val i = node.index.absoluteValue
        val v = variable(i)
        model[v - 1] = true
        if (_oneSat(high(i), parity, model)) {
            return true
        }
        model[v - 1] = false
        if (_oneSat(low(i), parity xor low(i).negated, model)) {
            return true
        }
        return false
    }

    fun oneSat(node: Ref, n: Int): List<Boolean?> {
        // n - number of variables
        val model = MutableList<Boolean?>(n) { null }
        return if (_oneSat(node, node.negated, model)) {
            model
        } else {
            emptyList()
        }
    }
}

fun main() {
    val bdd = BDD()
    val one = bdd.one
    val zero = bdd.zero

    val x1 = bdd.mkVar(1)
    val x2 = bdd.mkVar(2)
    val x3 = bdd.mkVar(3)
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
    println("f = $f = ${bdd.getTriplet(f)}")
    println("e = $e = ${bdd.getTriplet(e)}")
    println("g = $g = ${bdd.getTriplet(g)}")
    println("bdd.size = ${bdd.size}, bdd.realSize() = ${bdd.realSize}")

    println("-".repeat(42))
    println("BDD nodes (${bdd.realSize}):")
    for (i in 1..bdd.size) {
        if (bdd.storage.isOccupied(i)) {
            if (i > 1) {
                println("$i (v=${bdd.variable(i)}, low=${bdd.low(i)}, high=${bdd.high(i)})")
            } else {
                println("$i (terminal)")
            }
        }
    }

    println("-".repeat(42))
    println("Collecting garbage...")
    bdd.collectGarbage(listOf(f, e, g))

    println("-".repeat(42))
    println("BDD nodes (${bdd.realSize}) after GC:")
    for (i in 1..bdd.size) {
        if (bdd.storage.isOccupied(i)) {
            if (i > 1) {
                println("$i (v=${bdd.variable(i)}, low=${bdd.low(i)}, high=${bdd.high(i)})")
            } else {
                println("$i (terminal)")
            }
        }
    }

    println("-".repeat(42))
    println("bdd.size = ${bdd.size}")
    println("bdd.realSize = ${bdd.realSize}")
    println("bdd.cacheHits = ${bdd.cacheHits}")
    println("bdd.cacheMisses = ${bdd.cacheMisses}")
    println("bdd.maxChain() = ${bdd.maxChain()}")
    // println("bdd.chains() = ${bdd.chains()}")
}
