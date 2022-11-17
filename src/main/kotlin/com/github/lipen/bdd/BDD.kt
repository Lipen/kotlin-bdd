package com.github.lipen.bdd

import kotlin.math.absoluteValue
import kotlin.math.min
import kotlin.math.pow

private val logger = mu.KotlinLogging.logger {}

class BDD(
    val storageCapacity: Int = 1 shl 20,
    val bucketsCapacity: Int = storageCapacity,
) {
    private val buckets = IntArray(bucketsCapacity)
    private val storage = Storage(storageCapacity)

    val one = Ref(1) // terminal 1
    val zero = Ref(-1) // terminal 0

    init {
        check(storage.alloc() == 1) // allocate the terminal node
        buckets[0] = 1 // store the terminal node in the 0th bucket
    }

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

    // TODO: allow customize cache size
    private val cache = OpCache()
    val cacheHits: Int get() = cache.hits
    val cacheMisses: Int get() = cache.misses

    internal fun isOccupied(i: Int): Boolean = storage.isOccupied(i)

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

    private val bitmask = bucketsCapacity - 1

    private fun lookup(v: Int, low: Ref, high: Ref): Int {
        // return cantor3(v, low.index.absoluteValue, high.index.absoluteValue) and bitmask
        @OptIn(ExperimentalUnsignedTypes::class)
        return pairing(
            v.toUInt().toULong(),
            low.index.toUInt().toULong(),
            high.index.toUInt().toULong(),
        ).toInt() and bitmask
    }

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
            logger.debug { "mk: duplicates $low == $high" }
            return low
        }

        val bucketIndex = lookup(v, low, high)
        logger.debug { "mk: bucketIndex for ($v, $low, $high) is $bucketIndex" }
        var index = buckets[bucketIndex]

        if (index == 0) {
            // Create new node and put it into the bucket
            val node = addNode(v, low, high)
            buckets[bucketIndex] = node.index
            logger.debug { "mk: created new node $node" }
            return node
        }

        while (true) {
            check(index > 0)

            if (variable(index) == v && low(index) == low && high(index) == high) {
                // The node already exists
                val node = Ref(index)
                logger.debug { "mk: node $node already exists" }
                return node
            }

            val next = next(index)

            if (next == 0) {
                // Create new node and append it to the bucket
                val node = addNode(v, low, high)
                storage.setNext(index, node.index)
                logger.debug { "mk: created new node $node after $index" }
                return node
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

    fun collectGarbage(roots: Iterable<Ref>) {
        logger.debug { "Collecting garbage..." }

        cache.clear()
        // caches.forEach { it.clear() }

        val alive = descendants(roots)
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

        if (isTerminal(node) || v < variable(node)) {
            return Pair(node, node)
        }
        check(v == variable(node))
        val i = node.index.absoluteValue
        return if (node.negated) {
            Pair(-low(i), -high(i))
        } else {
            Pair(low(i), high(i))
        }
    }

    fun applyIte(f: Ref, g: Ref, h: Ref): Ref {
        logger.debug { "applyIte(f = $f, g = $g, h = $h)" }

        // Terminal cases for ITE(F,G,H):
        // - One variable cases (F is constant)
        //   - ite(1,G,H) => G
        //   - ite(0,G,H) => H
        // - Replace variables with constants, if possible
        //   - (g==f) ite(F,F,H) => ite(F,1,H) => F+H => ~(~F*~H)
        //   - (h==f) ite(F,G,F) => ite(F,G,0) => ~F*H
        //   - (g==~f) ite(F,~F,H) => ite(F,0,H) => F*G
        //   - (h==~f) ite(F,G,~F) => ite(F,G,1) => ~F+G
        // - Remaining one variable cases
        //   - (h==g) ite(F,G,G) => G
        //   - (h==~g) ite(F,G,~G) => F<->G => F^~G
        //   - ite(F,1,0) => F
        //   - ite(F,0,1) => ~F

        // ite(1,G,H) => G
        if (isOne(f)) {
            logger.debug { "applyIte: f is 1" }
            return g
        }
        // ite(0,G,H) => H
        if (isZero(f)) {
            logger.debug { "applyIte: f is 0" }
            return h
        }

        // From now one, F is known not to be a constant
        check(!isTerminal(f))

        // ite(F,F,H) == ite(F,1,H) == F + H
        if (isOne(g) || f == g) {
            logger.debug { "applyIte: either g is 1 or f == g" }
            return applyAnd(f, h)
        }
        // ite(F,~F,H) == ite(F,0,H) == ~F * H
        else if (isZero(g) || f == -g) {
            logger.debug { "applyIte: either g is 0 or f == ~g" }
            return applyAnd(-f, h)
        }

        // ite(F,G,F) == ite(F,G,0) == F * G
        if (isZero(h) || f == h) {
            logger.debug { "applyIte: either h is 0 or f == h" }
            return applyAnd(f, g)
        }
        // ite(F,G,~F) == ite(F,G,1) == ~F + G
        else if (isOne(h) || f == -h) {
            logger.debug { "applyIte: either h is 1 or f == ~h" }
            return applyOr(-f, g)
        }

        // ite(F,G,G) => G
        if (g == h) {
            logger.debug { "applyIte: g == h" }
            return g
        }
        // ite(F,G,~G) == F <-> G == F ^ ~G
        else if (g == -h) {
            logger.debug { "applyIte: g == ~h" }
            return applyXor(f, h)
        }

        // From here, there are no constants
        check(!isTerminal(g))
        check(!isTerminal(h))

        // Make sure the first two pointers (f and g) are regular (not negated)
        @Suppress("NAME_SHADOWING") var f = f
        @Suppress("NAME_SHADOWING") var g = g
        @Suppress("NAME_SHADOWING") var h = h
        // ite(!F,G,H) => ite(F,H,G)
        if (f.negated) {
            f = -f
            val tmp = g
            g = h
            h = tmp
        }
        var n = false
        // ite(F,!G,H) => !ite(F,G,!H)
        if (g.negated) {
            g = -g
            h = -h
            n = true
        }

        return cache.op(OpKind.Ite, listOf(f, g, h)) {
            val i = variable(f)
            val j = variable(g)
            val k = variable(h)
            val m = min(i, min(j, k))
            logger.debug { "applyIte: min variable = $m" }

            // cofactors of f,g,h
            val (f0, f1) = topCofactors(f, m)
            logger.debug { "applyIte: cofactors of f = $f:" }
            logger.debug { "    f0 = $f0" }
            logger.debug { "    f1 = $f1" }
            val (g0, g1) = topCofactors(g, m)
            logger.debug { "applyIte: cofactors of g = $g:" }
            logger.debug { "    g0 = $g0" }
            logger.debug { "    g1 = $g1" }
            val (h0, h1) = topCofactors(h, m)
            logger.debug { "applyIte: cofactors of h = $h:" }
            logger.debug { "    h0 = $h0" }
            logger.debug { "    h1 = $h1" }

            // cofactors of the resulting node ("then" and "else" branches)
            val t = applyIte(f1, g1, h1)
            val e = applyIte(f0, g0, h0)

            logger.debug { "applyIte: cofactors of res:" }
            logger.debug { "    t = $t" }
            logger.debug { "    e = $e" }
            mkNode(v = m, low = e, high = t).let {
                if (n) -it else it
            }
        }.also {
            logger.debug { "applyIte(f = $f, g = $g, h = $h) = $it" }
        }
    }

    fun applyAnd_ite(u: Ref, v: Ref): Ref {
        logger.debug { "applyAnd_ite(u = $u, v = $v)" }
        return applyIte(u, v, zero)
    }

    fun applyOr_ite(u: Ref, v: Ref): Ref {
        logger.debug { "applyOr_ite(u = $u, v = $v)" }
        return applyIte(u, one, v)
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

        return cache.op(OpKind.And, listOf(u, v)) {
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

        return cache.op(OpKind.Or, listOf(u, v)) {
            _apply(u, v, ::applyOr)
        }
    }

    fun applyXor(u: Ref, v: Ref): Ref {
        logger.debug { "applyXor(u = $u, v = $v)" }

        if (isOne(u)) {
            logger.debug { "applyXor(1, v) = -v" }
            return -v
        }
        if (isOne(v)) {
            logger.debug { "applyXor(u, 1) = -u" }
            return -u
        }
        if (isZero(u)) {
            logger.debug { "applyXor(0, v) = v" }
            return v
        }
        if (isZero(v)) {
            logger.debug { "applyXor(u, 0) = u" }
            return u
        }
        if (u == v) {
            logger.debug { "applyXor(x, x) = 0" }
            return zero
        }
        if (u == -v) {
            logger.debug { "applyXor(x, -x) = 1" }
            return one
        }

        // val uVar = variable(u)
        // val vVar = variable(v)
        // if ((uVar > vVar) || (uVar == vVar && u.index > v.index)) {
        //     return xorCache.getOrCompute(Pair(v, u)) {
        //         _apply(v, u, ::applyXor)
        //     }
        // }

        return cache.op(OpKind.Xor, listOf(u, v)) {
            _apply(u, v, ::applyXor)
        }
    }

    fun descendants(nodes: Iterable<Ref>): Set<Int> {
        val visited = mutableSetOf(1)

        fun visit(node: Ref) {
            val i = node.index.absoluteValue
            if (visited.add(i)) {
                visit(low(i))
                visit(high(i))
            }
        }

        for (node in nodes) {
            visit(node)
        }

        return visited
    }

    fun descendants(node: Ref): Set<Int> {
        return descendants(listOf(node))
    }

    fun size(node: Ref): Int {
        return cache.get(OpKind.Size, listOf(node)) {
            descendants(node).size
        }
    }

    private fun _count(node: Ref, max: Double, cache: MapCache<Ref, Double>): Double {
        if (isOne(node)) {
            return max
        } else if (isZero(node)) {
            return 0.0
        }

        return cache.getOrCompute(node) {
            val low = low(node).let { if (node.negated) -it else it }
            val high = high(node).let { if (node.negated) -it else it }

            val countLow = _count(low, max, cache)
            val countHigh = _count(high, max, cache)

            (countLow + countHigh) / 2.0
        }
    }

    fun count(node: Ref, nvars: Int): Long {
        val cache = MapCache<Ref, Double>("COUNT")
        // TODO: determine `nvars` automatically
        // TODO: calculate `max` correctly
        val max = 2L.toDouble().pow(nvars) //.toLong()
        return _count(node, max, cache).toLong()
    }

    private fun _substitute(f: Ref, v: Int, g: Ref, cache: OpCache): Ref {
        logger.debug { "substitute(f = $f, v = $v, g = $g)" }

        if (isTerminal(f)) {
            return f
        }

        val i = variable(f)
        check(i > 0)
        if (v < i) {
            return f
        }

        return cache.op(OpKind.Substitute, listOf(f, g)) {
            if (i == v) {
                val low = low(f)
                val high = high(f)

                applyIte(g, high, low).let { if (f.negated) -it else it }
            } else {
                check(v > i)

                // val j = variable(g)
                val j = if (variable(g) > 0) variable(g) else variable(f)
                check(j > 0)
                val m = min(i, j)
                check(m > 0)

                val (f0, f1) = topCofactors(f, m)
                val (g0, g1) = topCofactors(g, m)
                val h0 = _substitute(f0, v, g0, cache)
                val h1 = _substitute(f1, v, g1, cache)

                mkNode(v = m, low = h0, high = h1)
            }
        }
    }

    fun substitute(f: Ref, v: Int, g: Ref): Ref {
        val cache = OpCache()
        return _substitute(f, v, g, cache)
    }

    private fun _exists(node: Ref, j: Int, vars: Set<Int>, cache: MapCache<Ref, Ref>): Ref {
        logger.debug { "_exists($node, $vars) ($node = ${getTriplet(node)})" }

        if (isTerminal(node)) {
            return node
        }

        return cache.getOrCompute(node) {
            val v = variable(node)
            val low = low(node).let { if (node.negated) -it else it }
            val high = high(node).let { if (node.negated) -it else it }

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
        val cache = MapCache<Ref, Ref>("EXISTS($vars)")
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
        cache: MapCache<Triple<Ref, Ref, Set<Int>>, Ref>,
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
        val relProductCache: MapCache<Triple<Ref, Ref, Set<Int>>, Ref> = MapCache("RELPROD($vars)")
        return _relProduct(f, g, vars, relProductCache)
    }

    private fun _oneSat(node: Ref, parity: Boolean, model: MutableList<Boolean?>): Boolean {
        if (isTerminal(node)) {
            return parity
        }
        val v = variable(node)
        model[v - 1] = true
        val high = high(node)
        if (_oneSat(high, parity, model)) {
            return true
        }
        model[v - 1] = false
        val low = low(node)
        if (_oneSat(low, parity xor low.negated, model)) {
            return true
        }
        return false
    }

    fun oneSat(node: Ref, n: Int): List<Boolean?> {
        // n - number of variables
        val model = MutableList<Boolean?>(n) { null }
        val ok = _oneSat(node, !node.negated, model)
        return if (ok) model else emptyList()
    }

    fun toBracketString(node: Ref): String {
        if (isZero(node)) {
            return "(0)"
        } else if (isOne(node)) {
            return "(1)"
        }

        val v = variable(node)
        val low = low(node).let { if (node.negated) -it else it }
        val high = high(node).let { if (node.negated) -it else it }
        return "(x$v, ${toBracketString(high)}, ${toBracketString(low)})"
    }

    fun toGraphVizLines(roots: Iterable<Ref>): Sequence<String> {
        @Suppress("NAME_SHADOWING")
        val roots = roots.toSet()
        val allNodeIds = descendants(roots).sorted()

        return sequence {
            yield("graph {")

            yield("  // Nodes")
            yield("  { node [shape=circle, fixedsize=true];")
            for (id in allNodeIds) {
                check(id > 0)
                if (id == 1) continue

                val label = "\\N:x${variable(id)}"
                yield("  $id [label=\"$label\"];")
            }
            yield("  }")

            yield("")
            yield("  // Terminal")
            yield("  { rank=sink; 1 [shape=square, fixedsize=true]; }")

            yield("")
            yield("  // Roots")
            yield("  { rank=source; node[shape=square, fixedsize=true, style=rounded];")
            for (ref in roots) {
                yield("  \"$ref\";")
            }
            yield("  }")

            yield("")
            yield("  // Root-edges")
            yield("  { edge[style=solid];")
            for (ref in roots) {
                val id = ref.index.absoluteValue
                yield("  \"$ref\" -- $id;")
            }
            yield("  }")

            yield("")
            yield("  // Edges")
            for (id in allNodeIds) {
                if (id == 1) continue
                val high = high(id)
                check(!high.negated)
                yield("  $id -- ${high.index} [style=solid, tailport=sw];")
                val low = low(id)
                if (low.negated) {
                    yield("  $id -- ${-low.index} [style=dashed, tailport=se, label=-1];")
                } else {
                    yield("  $id -- ${low.index} [style=dashed, tailport=se];")
                }
            }

            yield("}")
        }
    }
}

fun testSuite1() {
    val bdd = BDD()

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
    println("f = $f = ${bdd.toBracketString(f)}")
    println("-".repeat(42))
    val e = bdd.exists(f, 3)

    println("-".repeat(42))
    println("f = $f = ${bdd.getTriplet(f)}")
    println("e = $e = ${bdd.getTriplet(e)}")
    println("g = $g = ${bdd.getTriplet(g)}")
    println("bdd.size = ${bdd.size}, bdd.realSize() = ${bdd.realSize}")

    println("GraphViz for [f,e,g]:")
    for (line in bdd.toGraphVizLines(listOf(f, e, g))) {
        println(line)
    }

    println("-".repeat(42))
    println("BDD nodes (${bdd.realSize}):")
    for (i in 1..bdd.size) {
        if (bdd.isOccupied(i)) {
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
        if (bdd.isOccupied(i)) {
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
    println("-".repeat(42))
}

fun testSuite2() {
    val bdd = BDD()

    val c1 = bdd.clause(1, 3, 5, 6)
    val c2 = bdd.clause(-2, 1, 3, -6)
    val c3 = bdd.clause(-3, 4, 5)
    val f = bdd.applyAnd(bdd.applyAnd(c1, c2), c3)

    println("-".repeat(42))
    println("f = $f = ${bdd.toBracketString(f)}")
    println("GraphViz for [f]:")
    for (line in bdd.toGraphVizLines(listOf(f))) {
        println(line)
    }

    println("-".repeat(42))
    println("bdd.size = ${bdd.size}")
    println("bdd.realSize = ${bdd.realSize}")
    println("-".repeat(42))
}

fun testSuite3() {
    val bdd = BDD()

    var f = bdd.zero
    for (c in listOf(
        bdd.cube(-1, -2, -3, 4),
        bdd.cube(-1, -2, 3, -4),
        bdd.cube(-1, 2, -3, 4),
        bdd.cube(1, 2, -3, -4),
        bdd.cube(1, 2, 3, 4),
    )) {
        f = bdd.applyOr(f, c)
    }

    println("-".repeat(42))
    println("bdd.size = ${bdd.size}")
    println("bdd.realSize = ${bdd.realSize}")
    println("-".repeat(42))

    println("-".repeat(42))
    println("f = $f = ${bdd.toBracketString(f)}")
    println("GraphViz for [f]:")
    for (line in bdd.toGraphVizLines(listOf(f))) {
        println(line)
    }
}

fun testSuite4() {
    val bdd = BDD()

    var f = bdd.zero
    for (c in listOf(
        bdd.applyAnd(bdd.mkVar(1), bdd.mkVar(3)),
        bdd.applyAnd(bdd.mkVar(2), bdd.mkVar(4)),
    )) {
        f = bdd.applyOr(f, c)
    }

    println("-".repeat(42))
    println("bdd.size = ${bdd.size}")
    println("bdd.realSize = ${bdd.realSize}")
    println("-".repeat(42))

    println("-".repeat(42))
    println("f = $f = ${bdd.toBracketString(f)}")
    println("GraphViz for [f]:")
    val lines = bdd.toGraphVizLines(listOf(f)).toList()
    for (line in lines) {
        println(line)
    }
}

fun main() {
    testSuite1()
    testSuite2()
    testSuite3()
    testSuite4()
}
