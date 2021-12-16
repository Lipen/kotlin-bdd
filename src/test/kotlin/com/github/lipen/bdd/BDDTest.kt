package com.github.lipen.bdd

import kotlin.test.Test
import kotlin.test.assertEquals

internal class BDDTest {
    private val bdd = BDD(storageCapacity = 1 shl 5)

    @Test
    fun `empty BDD`() {
        assertEquals(1, bdd.size, "bdd.size")
    }

    @Test
    fun `BDD for variable`() {
        val x = bdd.mkVar(2)
        assertEquals(2, x.index, "x.index")
        assertEquals(2, bdd.size, "bdd.size")
    }

    @Test
    fun `BDD for negated variable`() {
        val x = bdd.mkVar(-2)
        assertEquals(-2, x.index, "x.index")
        assertEquals(2, bdd.size, "bdd.size")
    }

    @Test
    fun `BDD for clause`() {
        val c = bdd.clause(1, 2, 3)
        assertEquals(4, c.index, "c.index")
        assertEquals(4, bdd.size, "bdd.size")
    }

    @Test
    fun `BDD for cube`() {
        val c = bdd.cube(1, 2, 3)
        assertEquals(4, c.index, "c.index")
        assertEquals(4, bdd.size, "bdd.size")
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
}
