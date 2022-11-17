package com.github.lipen.bdd

import kotlin.test.Test
import kotlin.test.assertEquals

internal class BDDTest {
    private val bdd = BDD(storageBits = 16)

    @Test
    fun `empty BDD`() {
        assertEquals(1, bdd.size, "bdd.size")
    }

    @Test
    fun `BDD for variable`() {
        val x = bdd.mkVar(2)
        assertEquals(Ref(2), x, "x")
        assertEquals(2, bdd.size, "bdd.size")
    }

    @Test
    fun `BDD for negated variable`() {
        val x = bdd.mkVar(-2)
        assertEquals(Ref(-2), x, "x")
        assertEquals(2, bdd.size, "bdd.size")
    }

    @Test
    fun `BDD for clause`() {
        val c = bdd.clause(-1, 2, -3)
        val x1 = bdd.mkVar(1)
        val x2 = bdd.mkVar(2)
        val x3 = bdd.mkVar(3)
        val c2 = bdd.applyOr(bdd.applyOr(-x1, x2), -x3)
        assertEquals(c2, c)
    }

    @Test
    fun `BDD for cube`() {
        val c = bdd.cube(-1, 2, -3)
        val x1 = bdd.mkVar(1)
        val x2 = bdd.mkVar(2)
        val x3 = bdd.mkVar(3)
        val c2 = bdd.applyAnd(bdd.applyAnd(-x1, x2), -x3)
        assertEquals(c2, c)
    }

    @Test
    fun substitution0() {
        val f = -bdd.mkVar(1)
        val f0 = bdd.substitute(f, 1, bdd.zero)
        assertEquals(bdd.one, f0)
        val f1 = bdd.substitute(f, 1, bdd.one)
        assertEquals(bdd.zero, f1)
    }

    @Test
    fun substitution1() {
        val x1 = bdd.mkVar(1)
        val x2 = bdd.mkVar(2)
        val f = bdd.applyOr(-x1, -x2)
        // c1 == -(x3 /\ x4 /\ x5) \/ -x2
        val c1 = bdd.substitute(f, 1, bdd.cube(3, 4, 5))
        // c2 == -x2 \/ -x3 \/ -x4 \/ -x5
        val c2 = bdd.clause(-2, -3, -4, -5)
        assertEquals(c2, c1)
    }

    @Test
    fun substitution2() {
        val x1 = bdd.mkVar(1)
        val x2 = bdd.mkVar(2)
        val f = bdd.applyOr(-x1, x2)
        // c1 == -(x3 /\ x4 /\ x5) \/ x2
        val c1 = bdd.substitute(f, 1, bdd.cube(3, 4, 5))
        // c2 == x2 \/ -x3 \/ -x4 \/ -x5
        val c2 = bdd.clause(2, -3, -4, -5)
        assertEquals(c2, c1)
    }

    @Test
    fun `existential quantification`() {
        val c1 = bdd.cube(-1, -2, 3)
        val c2 = bdd.cube(1, -3)
        val c3 = bdd.cube(1, 2)
        val f = bdd.applyOr(bdd.applyOr(c1, c2), c3)
        val g = bdd.exists(f, 3)
        val h = bdd.clause(1, -2)

        assertEquals(h, g, "∃x₃.((~x₁∧~x₂∧~x₃) ∨ (x₁∧~x₃) ∨ (x₁∧x₂)) must be equal to (x₁∨~x₂)")
        assertEquals(10, bdd.size, "bdd.size")
    }

    @Test
    fun `count for cube`() {
        val f = bdd.cube(1, 2, 3)
        assertEquals(1, bdd.count(f, 3))
    }

    @Test
    fun `count for clause`() {
        val f = bdd.clause(1, 2, 3)
        assertEquals(7, bdd.count(f, 3))
    }

    private fun `test all n-ary Boolean functions`(n: Int) {
        for (f in 0 until (1 shl (1 shl n))) {
            var node = bdd.zero
            for ((i, c) in f.toString(2).reversed().withIndex()) {
                if (c.toBoolean()) {
                    val lits = i
                        .toString(2)
                        .padStart(n, '0')
                        .withIndex()
                        .map { (j, x) ->
                            if (x.toBoolean()) {
                                j + 1
                            } else {
                                -(j + 1)
                            }
                        }
                    val cube = bdd.cube(lits)
                    node = bdd.applyOr(node, cube)
                }
            }
            val num = f.toString(2).count { it.toBoolean() }
            assertEquals(num.toLong(), bdd.count(node, n), "f = $f")
        }
    }

    @Test
    fun `all 2-ary functions`() {
        `test all n-ary Boolean functions`(2)
    }

    @Test
    fun `all 3-ary functions`() {
        `test all n-ary Boolean functions`(3)
    }

    @Test
    fun `all 4-ary functions`() {
        `test all n-ary Boolean functions`(4)
    }
}

private fun Char.toBoolean(): Boolean {
    return when (this) {
        '0' -> false
        '1' -> true
        else -> error("Bad char '$this'")
    }
}
