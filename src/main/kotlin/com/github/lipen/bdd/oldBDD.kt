package com.github.lipen.bdd

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Optional
import kotlin.math.absoluteValue
import kotlin.math.min

private val logger = KotlinLogging.logger {}

class OldBDD {
    // T :: {id: Node}
    private val _nodes: MutableMap<Int, Node> = mutableMapOf(1 to Terminal)
    val nodes: Map<Int, Node> = _nodes

    // H :: {(v,l,h): Function}
    private val uniqueTable: MutableMap<MyKey, Function> = mutableMapOf()

    private data class MyKey(
        val v: Int, // variable, positive
        val low: Int, // low id, signed
        val high: Int, // high id, positive(?)
    ) {
        constructor(v: Int, low: Function, high: Function) : this(v, low.id, high.id)
    }

    private val iteCache = MapCache<Triple<Int, Int, Int>, Function>("ITE")
    private val iteConstantCache = MapCache<Triple<Int, Int, Int>, Optional<Function>>("ITE-CONST")
    private val andCache = MapCache<Pair<Int, Int>, Function>("AND")
    private val orCache = MapCache<Pair<Int, Int>, Function>("OR")
    private val xorCache = MapCache<Pair<Int, Int>, Function>("XOR")
    private val caches = arrayOf(iteCache, iteConstantCache, andCache, orCache, xorCache)
    val cacheMisses: Int
        get() = caches.sumOf { it.misses }

    operator fun get(id: Int): Function {
        val node = _nodes.getValue(id.absoluteValue)
        return Function(node, id < 0)
    }

    private fun addNode(node: Node): Int {
        val id: Int = (1..Int.MAX_VALUE).first { it !in _nodes }
        _nodes[id] = node
        return id
    }

    val one: Function = Function(Terminal, false)
    val zero: Function = Function(Terminal, true)

    fun terminal(value: Boolean): Function {
        return if (value) one else zero
    }

    fun variable(v: Int): Function {
        return mk(v, low = zero, high = one)
    }

    fun mk(v: Int, low: Node, high: Node): Function {
        return mk(v, low = Function(low), high = Function(high))
    }

    fun mk(v: Int, low: Function, high: Function): Function {
        require(v > 0)
        logger.debug { "mk(v = $v, low = $low, high = $high)" }

        // Handle canonicity
        if (high.negated) {
            logger.debug { "mk: restoring canonicity" }
            return !mk(v, !low, !high)
        }

        assert(!high.negated)

        // Handle duplicates
        if (low == high) {
            logger.debug { "mk: duplicates" }
            return low
        }

        return uniqueTable.computeIfAbsent(MyKey(v, low, high)) {
            // println("mk: Cache miss for $it")
            val node = VarNode(v, low, high)
            Function(node, false)
        }
    }

    fun applyIte(f: Function, g: Function, h: Function): Function {
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
        if (f.isOne()) {
            logger.debug { "applyIte: f is 1" }
            return g
        }
        // ite(0,G,H) => H
        if (f.isZero()) {
            logger.debug { "applyIte: f is 0" }
            return h
        }

        // From now one, F is known not to be a constant
        f.node as VarNode

        // ite(F,F,H) == ite(F,1,H) == F + H
        if (g.isTerminal() || f == g) {
            logger.debug { "applyIte: either g is terminal or f == g" }
            @Suppress("LiftReturnOrAssignment")
            // ite(F,1,0) => F
            if (h.isZero()) {
                logger.debug { "applyIte: h is 0" }
                return f
            }
            // F + H => ~(~F * ~H)
            else {
                logger.debug { "applyIte: h is not 0" }
                return !applyAnd(!f, !h)
            }
        }
        // ite(F,~F,H) == ite(F,0,H) == ~F * H
        else if (g.isZero() || f == !g) {
            logger.debug { "applyIte: either g is 0 or f == ~g" }
            @Suppress("LiftReturnOrAssignment")
            // ite(F,0,1) => ~F
            if (h.isOne()) {
                logger.debug { "applyIte: h is 1" }
                return !f
            }
            // ~F * H
            else {
                logger.debug { "applyIte: h is not 1" }
                return applyAnd(!f, h)
            }
        }

        // ite(F,G,F) == ite(F,G,0) == F * G
        if (h.isZero() || f == h) {
            logger.debug { "applyIte: either h is 0 or f == h" }
            return applyAnd(f, g)
        }
        // ite(F,G,~F) == ite(F,G,1) == ~F + G
        else if (h.isOne() || f == !h) {
            logger.debug { "applyIte: either h is 1 or f == ~h" }
            return applyAnd(f, !g)
        }

        // ite(F,G,G) => G
        if (g == h) {
            logger.debug { "applyIte: g == h" }
            return g
        }
        // ite(F,G,~G) == F <-> G == F ^ ~G
        else if (g == !h) {
            logger.debug { "applyIte: g == ~h" }
            return applyXor(f, h)
        }

        // From here, there are no constants
        g.node as VarNode
        h.node as VarNode

        // Make sure the first two pointers (f and g) are regular (not negated)
        @Suppress("NAME_SHADOWING") var f = f
        @Suppress("NAME_SHADOWING") var g = g
        @Suppress("NAME_SHADOWING") var h = h
        // ite(!F,G,H) => ite(F,H,G)
        if (f.negated) {
            f = !f
            val tmp = g
            g = h
            h = tmp
        }
        var n = false
        // ite(F,!G,H) => !ite(F,G,!H)
        if (g.negated) {
            g = !g
            h = !h
            n = true
        }

        return iteCache.getOrCompute(Triple((f.node as VarNode).v, g.id, h.id)) {
            // f.node as VarNode
            // g.node as VarNode
            // h.node as VarNode
            val i = (f.node as VarNode).v
            val j = (g.node as VarNode).v
            val k = (h.node as VarNode).v
            val m = min(i, min(j, k))
            logger.debug { "applyIte: min variable = $m" }

            // cofactors of f,g,h
            val (f0, f1) = f.topCofactors(m)
            logger.debug { "applyIte: cofactors of f = $f:" }
            logger.debug { "    f0 = $f0" }
            logger.debug { "    f1 = $f1" }
            val (g0, g1) = g.topCofactors(m)
            logger.debug { "applyIte: cofactors of g = $g:" }
            logger.debug { "    g0 = $g0" }
            logger.debug { "    g1 = $g1" }
            val (h0, h1) = h.topCofactors(m)
            logger.debug { "applyIte: cofactors of h = $h:" }
            logger.debug { "    h0 = $h0" }
            logger.debug { "    h1 = $h1" }

            // cofactors of the resulting node ("then" and "else" branches)
            val t = applyIte(f1, g1, h1)
            val e = applyIte(f0, g0, h0)

            logger.debug { "applyIte: cofactors of res:" }
            logger.debug { "    t = $t" }
            logger.debug { "    e = $e" }
            mk(v = m, low = e, high = t).let {
                if (n) !it else it
            }.also {
                logger.debug { "applyIte: res = $it" }
            }
        }
    }

    fun iteConstant(f: Function, g: Function, h: Function): Function? {

        // NOTE: THIS METHOD IS INCOMPLETE AND PROBABLY CONTAINS BUGS!!!
        logger.error("NOTE: iteConstant implementation is incomplete!")

        logger.debug { "iteConstant(f = $f, g = $g, h = $h)" }

        // ite(1,G,H) => G
        if (f.isOne()) {
            logger.debug { "iteConstant: f is 1" }
            return g
        }
        // ite(0,G,H) => H
        if (f.isZero()) {
            logger.debug { "iteConstant: f is 0" }
            return h
        }

        // From now one, F is known not to be a constant
        f.node as VarNode

        // Replace g with constant if possible
        @Suppress("NAME_SHADOWING", "CascadeIf")
        val g = if (f == g) {
            one
        } else if (f == !g) {
            zero
        } else {
            g
        }

        // Replace h with constant if possible
        @Suppress("NAME_SHADOWING", "CascadeIf")
        val h = if (f == h) {
            zero
        } else if (f == !h) {
            one
        } else {
            h
        }

        // ite(F,G,G) => G
        if (g == h) {
            logger.debug { "iteConstant: g == h" }
            return g
        }
        // ite(F,1,0) or ite(F,0,1) => is not constant
        if (g.isTerminal() && h.isTerminal()) {
            logger.debug { "iteConstant: g and h are terminals" }
            return null
        }
        // ite(F,G,~G) == F <-> G == F ^ ~G
        if (g == !h) {
            logger.debug { "iteConstant: g == ~h" }
            return null
        }

        g.node as VarNode
        h.node as VarNode

        return iteConstantCache.getOrCompute(Triple(f.node.v, g.id, h.id)) {
            val i = f.node.v
            val j = g.node.v
            val k = h.node.v
            val m = min(i, min(j, k))
            logger.debug { "iteConstant: min variable = $m" }

            // cofactors of f,g,h
            val (f0, f1) = f.topCofactors(m)
            logger.debug { "iteConstant: cofactors of f = $f:" }
            logger.debug { "    f0 = $f0" }
            logger.debug { "    f1 = $f1" }
            val (g0, g1) = g.topCofactors(m)
            logger.debug { "iteConstant: cofactors of g = $g:" }
            logger.debug { "    g0 = $g0" }
            logger.debug { "    g1 = $g1" }
            val (h0, h1) = h.topCofactors(m)
            logger.debug { "iteConstant: cofactors of h = $h:" }
            logger.debug { "    h0 = $h0" }
            logger.debug { "    h1 = $h1" }

            val t = iteConstant(f1, g1, h1) ?: return@getOrCompute Optional.empty()
            val e = iteConstant(f0, g0, h0)
            if (e != t) {
                return@getOrCompute Optional.empty()
            }
            Optional.of(t)
        }.orElse(null)
    }

    fun applyAnd_ite(u: Function, v: Function): Function {
        logger.debug { "applyAnd_ite(u = $u, v = $v)" }
        return applyIte(u, v, zero)
    }

    private fun _apply(u: Function, v: Function, f: (Function, Function) -> Function): Function {
        logger.debug { "_apply(u = $u, v = $v)" }

        require(u.node is VarNode)
        require(v.node is VarNode)
        // u.node as VarNode
        // v.node as VarNode

        val i = u.node.v
        val j = v.node.v
        val m = min(i, j)
        logger.debug { "_apply(@${u.id}, @${v.id}): min variable = $m" }

        // cofactors of u,v
        val (u0, u1) = u.topCofactors(m)
        logger.debug { "_apply(@${u.id}, @${v.id}): cofactors of u = $u:" }
        logger.debug { "    u0 = $u0" }
        logger.debug { "    u1 = $u1" }
        val (v0, v1) = v.topCofactors(m)
        logger.debug { "_apply(@${u.id}, @${v.id}): cofactors of v = $v:" }
        logger.debug { "    v0 = $v0" }
        logger.debug { "    v1 = $v1" }

        // cofactors of the resulting node w
        val w0 = f(u0, v0)
        val w1 = f(u1, v1)
        logger.debug { "_apply(@${u.id}, @${v.id}): cofactors of w:" }
        logger.debug { "    w0 = $w0" }
        logger.debug { "    w1 = $w1" }

        return mk(v = m, low = w0, high = w1).also {
            logger.debug { "_apply(@${u.id}, @${v.id}): w = $it" }
        }
    }

    fun applyAnd(u: Function, v: Function): Function {
        logger.debug { "applyAnd(u = $u, v = $v)" }

        if (u.isZero() || v.isZero()) {
            logger.debug { "applyAnd(@${u.id}, @${v.id}): either u or v is 0" }
            return zero
        }
        if (u.isOne()) {
            logger.debug { "applyAnd(@${u.id}, @${v.id}): u is 1" }
            return v
        }
        if (v.isOne()) {
            logger.debug { "applyAnd(@${u.id}, @${v.id}): v is 1" }
            return u
        }
        if (u == v) {
            logger.debug { "applyAnd(@${u.id}, @${v.id}): u == v" }
            return u
        }
        if (u == !v) {
            logger.debug { "applyAnd(@${u.id}, @${v.id}): u == ~v" }
            return zero
        }

        return andCache.getOrCompute(Pair(u.id, v.id)) {
            _apply(u, v, ::applyAnd)
        }
    }

    fun applyOr(u: Function, v: Function): Function {
        logger.debug { "applyOr(u = $u, v = $v)" }

        if (u.isOne() || v.isOne()) {
            logger.debug { "applyOr(@${u.id}, @${v.id}): either u or v is 1" }
            return one
        }
        if (u.isZero()) {
            logger.debug { "applyOr(@${u.id}, @${v.id}): u is 0" }
            return v
        }
        if (v.isZero()) {
            logger.debug { "applyOr(@${u.id}, @${v.id}): v is 0" }
            return u
        }
        if (u == v) {
            logger.debug { "applyOr(@${u.id}, @${v.id}): u == v" }
            return u
        }
        if (u == !v) {
            logger.debug { "applyOr(@${u.id}, @${v.id}): u == ~v" }
            return one
        }

        return orCache.getOrCompute(Pair(u.id, v.id)) {
            _apply(u, v, ::applyOr)
        }
    }

    fun applyXor(u: Function, v: Function): Function {
        logger.debug { "applyXor(u = $u, v = $v)" }
        error("not checked yet")

        if (u.isOne()) {
            logger.debug { "applyXor: u is 1" }
            return !v
        }
        if (v.isOne()) {
            logger.debug { "applyXor: v is 1" }
            return !u
        }
        if (u.isZero()) {
            logger.debug { "applyXor: u is 0" }
            return v
        }
        if (v.isZero()) {
            logger.debug { "applyXor: v is 0" }
            return u
        }
        if (u == v) {
            logger.debug { "applyXor: u == v" }
            return zero
        }
        if (u == !v) {
            logger.debug { "applyXor: u == ~v" }
            return one
        }

        return xorCache.getOrCompute(Pair(u.id, v.id)) {
            _apply(u, v, ::applyXor)
        }
    }

    sealed interface Node {
        val id: Int
    }

    object Terminal : Node {
        override val id: Int = 1

        override fun toString(): String {
            return "⊤"
        }
    }

    inner class VarNode(
        val v: Int, // variable index
        val low: Function, // low-edge
        val high: Function, // high-edge
    ) : Node {
        override val id: Int = addNode(this)

        init {
            logger.debug { "Created new node $this" }
            check(id > 0)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as VarNode

            if (id != other.id) return false

            return true
        }

        override fun hashCode(): Int {
            return id
        }

        override fun toString(): String {
            return "@$id(v = $v, l = ${low.id}, h = ${high.id})"
        }
    }

    inner class Function(
        val node: Node,
        val negated: Boolean = false,
    ) {
        val id: Int = node.id.let { if (negated) -it else it }

        fun isTerminal(): Boolean {
            return node is Terminal
        }

        fun isOne(): Boolean {
            // return node is Terminal && !negated
            // return this == one
            return id == 1
        }

        fun isZero(): Boolean {
            // return node is Terminal && negated
            // return this == zero
            return id == -1
        }

        fun topCofactors(v: Int): Pair<Function, Function> {
            if (node is Terminal) {
                return Pair(this, this)
            }
            node as VarNode
            if (v < node.v) {
                return Pair(this, this)
            }
            check(v == node.v)
            return if (negated) {
                Pair(!node.low, !node.high)
            } else {
                Pair(node.low, node.high)
            }
        }

        private fun _descendants(visited: MutableSet<Int>) {
            for (i in visited) {
                assert(i > 0)
            }

            if (id == 1) {
                return
            }

            val r = id.absoluteValue
            if (visited.add(r)) {
                node as VarNode
                node.low._descendants(visited)
                node.high._descendants(visited)
            }
        }

        fun descendants(): Set<Int> {
            val visited = mutableSetOf(1)
            _descendants(visited)
            return visited
        }

        operator fun not(): Function {
            return Function(node, !negated)
        }

        infix fun and(other: Function): Function {
            return applyAnd(this, other)
        }

        infix fun or(other: Function): Function {
            return applyOr(this, other)
        }

        infix fun xor(other: Function): Function {
            return applyXor(this, other)
        }

        private fun _oneSat(v: Node, parity: Boolean, sat: MutableList<Tri>): Boolean {
            if (v is Terminal) {
                return parity
            }
            v as VarNode
            sat[v.v - 1] = Tri.True
            if (v.high._oneSat(v.high.node, parity, sat)) {
                return true
            }
            sat[v.v - 1] = Tri.False
            if (v.low._oneSat(v.low.node, parity xor v.low.negated, sat)) {
                return true
            }
            return false
        }

        fun oneSat(n: Int): List<Tri> {
            val answer = MutableList(n) { Tri.DontCare }
            return if (_oneSat(node, !negated, answer)) {
                answer
            } else {
                emptyList()
            }
        }

        private fun _allSat(partial: Map<Int, Boolean>, parity: Boolean): Sequence<Map<Int, Boolean>> {
            val p = parity xor negated
            if (node is Terminal) {
                return if (p) {
                    sequenceOf(partial)
                } else {
                    emptySequence()
                }
            }
            node as VarNode
            return sequence {
                val t = partial + Pair(node.v, true)
                yieldAll(node.high._allSat(t, p))
                val e = partial + Pair(node.v, false)
                yieldAll(node.low._allSat(e, p))
            }
        }

        fun allSat(): Sequence<Map<Int, Boolean>> {
            return _allSat(mutableMapOf(), true)
        }

        // fun eqNeg(other: Function) : Boolean {
        //     // return this == !other
        //     return (node == other.node) && (negated xor other.negated)
        // }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Function

            if (id != other.id) return false

            return true
        }

        override fun hashCode(): Int {
            return id
        }

        override fun toString(): String = when {
            isOne() -> "⊤"
            isZero() -> "⊥"
            else -> (if (negated) "~" else "") + node.toString()
        }
    }
}

enum class Tri {
    True, False, DontCare
}

private fun Map<Int, Boolean>.stringify(n: Int): String {
    return (1..n).joinToString("") { i ->
        when (this[i]) {
            true -> "1"
            false -> "0"
            null -> "x"
        }
    }
}

private fun f1() {
    val bdd = OldBDD()

    val x1 = bdd.variable(1)
    val x2 = bdd.variable(2)
    val w3 = ((x1 and x2) or !x1) and !x2
    println("w3 = $w3")
    println("-".repeat(42))

    // u = (x1 /\ x2) \/ ~x1
    val _x1 = bdd.variable(1)
    val u2 = bdd.mk(v = 2, low = bdd.zero, high = bdd.one)
    val u3 = bdd.mk(v = 1, low = bdd.one, high = u2)
    val u = u3
    println("u = (x1 * x2) + ~x1 = $u")

    // // u = (x1 /\ x2) \/ ~x1
    // val x1 = bdd.variable(1)
    // val x2 = bdd.variable(2)
    // val u2 = bdd.applyAnd(x1, x2)
    // val u3 = !x1
    // val u4 = bdd.applyOr(u2, u3)
    // val u = u4
    // println("u = $u")

    // v = ~x2
    val u4 = bdd.mk(v = 2, low = bdd.one, high = bdd.zero)
    val v = u4
    println("v = !x2 = $v")

    // w = (u /\ v)
    val w1 = bdd.applyAnd_ite(u, v)
    println("w1 = $w1")
    println("-".repeat(42))

    val w2 = bdd.applyAnd(u, v)
    println("w2 = $w2")
    println("-".repeat(42))

    println("w1 = $w1")
    println("w2 = $w2")
    println("w3 = $w3")

    println("-".repeat(42))
    println("bdd.cacheMisses = ${bdd.cacheMisses}")

    println("=".repeat(42))
}

private fun f2() {
    val bdd = OldBDD()

    // function u2 = x2
    val u2 = bdd.mk(v = 2, low = bdd.zero, high = bdd.one)
    println("u2 = $u2")
    println("-".repeat(42))

    // just x2
    val x2 = bdd.variable(2)
    println("x2 = $x2")
    println("-".repeat(42))

    // function u3 = !u2
    val u3 = !u2
    println("u3 = $u3")
    println("-".repeat(42))

    // just !x2
    val nx2 = !x2
    println("!x2 = $nx2")
    println("-".repeat(42))

    // function u4 = !x2
    val u4 = bdd.mk(v = 2, low = bdd.one, high = bdd.zero)
    println("u4 = $u4")

    println("-".repeat(42))
    println("bdd.cacheMisses = ${bdd.cacheMisses}")

    println("=".repeat(42))
}

private fun f3() {
    val bdd = OldBDD()
    val zero = bdd.zero
    val one = bdd.one

    val v1 = bdd.mk(v = 4, low = one, high = zero)
    val v3 = bdd.mk(v = 4, low = zero, high = one)
    val v4 = bdd.mk(v = 3, low = one, high = v1)
    val v5 = bdd.mk(v = 3, low = zero, high = v3)
    val v6 = bdd.mk(v = 2, low = v4, high = v5)
    val v7 = bdd.mk(v = 2, low = v3, high = v5)
    val v8 = bdd.mk(v = 1, low = v6, high = v7)
    val f = v8
    println("v1 = $v1")
    println("v3 = $v3")
    println("v4 = $v4")
    println("v5 = $v5")
    println("v6 = $v6")
    println("v7 = $v7")
    println("v8 = $v8")
    println("f = $f")

    println("-".repeat(42))
    val x1 = bdd.variable(1)
    val x2 = bdd.variable(2)
    val x3 = bdd.variable(3)
    val x4 = bdd.variable(4)
    println("x1 = $x1")
    println("x2 = $x2")
    println("x3 = $x3")
    println("x4 = $x4")

    println("-".repeat(42))
    val g = f and !x2
    println("g = $g")

    // println("-".repeat(42))
    // val h = g and !x3
    // println("h = $h")

    println("-".repeat(42))
    println("Nodes:")
    for ((i, node) in bdd.nodes.toSortedMap()) {
        println("  $i: $node")
    }
    println("Functions:")
    println("f = $f")
    println("g = $g")
    // println("h = $h")

    println("-".repeat(42))
    println("bdd.cacheMisses = ${bdd.cacheMisses}")

    println("=".repeat(42))
}

private fun f4() {
    val bdd = OldBDD()
    val nvar = 3

    val x1 = bdd.variable(1)
    val x2 = bdd.variable(2)
    val x3 = bdd.variable(3)
    println("x1 = $x1")
    println("x2 = $x2")
    println("x3 = $x3")

    println("-".repeat(42))
    val f = (!x1 or !x2) and (!x1 or !x3) and (!x2 or !x3)
    println("f = $f")

    println("-".repeat(42))
    val g = f and x2
    println("g = $g")

    println("-".repeat(42))
    val h = f and x2 and x3
    println("h = $h")

    println("-".repeat(42))
    println("Nodes:")
    for ((i, node) in bdd.nodes.toSortedMap()) {
        println("  $i: $node")
    }
    println("Functions:")
    println("f = $f")
    println("g = $g")
    println("h = $h")

    println("-".repeat(42))
    println("f.oneSat = ${f.oneSat(nvar)}")
    println("g.oneSat = ${g.oneSat(nvar)}")
    println("h.oneSat = ${h.oneSat(nvar)}")

    println("-".repeat(42))
    println("f.allSat = ${f.allSat().map { it.stringify(nvar) }.toList()}")
    println("g.allSat = ${g.allSat().map { it.stringify(nvar) }.toList()}")
    println("h.allSat = ${h.allSat().map { it.stringify(nvar) }.toList()}")

    println("-".repeat(42))
    println("bdd.cacheMisses = ${bdd.cacheMisses}")

    println("=".repeat(42))
}

private fun f5() {
    val bdd = OldBDD()
    val one = bdd.one
    val zero = bdd.zero
    val nvar = 3

    val x1 = bdd.variable(1)
    val x2 = bdd.variable(2)
    val x3 = bdd.variable(3)
    println("x1 = $x1")
    println("x2 = $x2")
    println("x3 = $x3")

    println("-".repeat(42))
    val v1 = bdd.applyIte(x2, bdd.applyIte(x3, one, zero), bdd.applyIte(x3, one, zero))
    val v2 = bdd.applyIte(x2, bdd.applyIte(x3, one, zero), zero)
    val f = bdd.applyIte(x1, v1, v2)
    println("f = $f")

    println("-".repeat(42))
    val g = f and !x3
    println("g = $g")

    println("-".repeat(42))
    println("Nodes:")
    for ((i, node) in bdd.nodes.toSortedMap()) {
        println("  $i: $node")
    }
    println("Functions:")
    println("f = $f")
    println("g = $g")

    println("-".repeat(42))
    println("f.allSat = ${f.allSat().map { it.stringify(nvar) }.toList()}")
    println("g.allSat = ${g.allSat().map { it.stringify(nvar) }.toList()}")

    println("-".repeat(42))
    println("bdd.cacheMisses = ${bdd.cacheMisses}")

    println("=".repeat(42))
}

private fun f6() {
    val bdd = OldBDD()
    val one = bdd.one
    val zero = bdd.zero
    val nvar = 4

    val x1 = bdd.variable(1)
    val x2 = bdd.variable(2)
    val x3 = bdd.variable(3)
    val x4 = bdd.variable(4)
    println("x1 = $x1")
    println("x2 = $x2")
    println("x3 = $x3")
    println("x4 = $x4")

    println("-".repeat(42))
    val f = (x1 or x2 or !x4) and x3 and (!x1 or x4)
    println("f = $f")

    println("-".repeat(42))
    val g = bdd.applyIte(x1, x3 and x4, x3 and ((x4 and x2) or (!x4 and one)))
    println("g = $g")

    println("-".repeat(42))
    println("Nodes:")
    for ((i, node) in bdd.nodes.toSortedMap()) {
        println("  $i: $node")
    }
    println("Functions:")
    println("f = $f")
    println("g = $g")

    println("-".repeat(42))
    println("f.allSat = ${f.allSat().map { it.stringify(nvar) }.toList()}")
    println("g.allSat = ${g.allSat().map { it.stringify(nvar) }.toList()}")

    println("-".repeat(42))
    println("bdd.cacheMisses = ${bdd.cacheMisses}")

    println("=".repeat(42))
}

private fun f7() {
    val bdd = OldBDD()
    val one = bdd.one
    val zero = bdd.zero
    val nvar = 2

    val x1 = bdd.variable(1)
    val x2 = bdd.variable(2)
    println("x1 = $x1")
    println("x2 = $x2")

    println("-".repeat(42))
    // val f = (x2 and !x1) or (!x2 and one)
    // val f = one
    val f = !x1 or !x2
    println("f = $f")

    println("-".repeat(42))
    // manually create OldBDD with reversed order
    val v1 = bdd.mk(v = 1, low = one, high = zero)
    val v2 = bdd.mk(v = 2, low = one, high = v1)
    val g = v2
    println("g = $g")

    println("-".repeat(42))
    println("Nodes:")
    for ((i, node) in bdd.nodes.toSortedMap()) {
        println("  $i: $node")
    }
    println("Functions:")
    println("f = $f")
    println("g = $g")

    println("-".repeat(42))
    println("f.allSat = ${f.allSat().map { it.stringify(nvar) }.toList()}")
    println("g.allSat = ${g.allSat().map { it.stringify(nvar) }.toList()}")

    println("-".repeat(42))
    println("bdd.cacheMisses = ${bdd.cacheMisses}")

    println("=".repeat(42))
}

fun main() {
    // f1()
    // f2()
    // f3()
    // f4()
    // f5()
    // f6()
    f7()
}
