@file:Suppress("FunctionName")

package com.github.lipen.bdd

import kotlin.math.absoluteValue

fun BDD.clause(literals: Iterable<Int>): Int {
    // TODO: check uniqueness and consistency
    var current = zero
    for (lit in literals.sortedByDescending { it.absoluteValue }) {
        val x = variable(lit)
        current = applyOr(current, x)
    }
    return current
}

fun BDD.clause_(literals: IntArray): Int {
    return clause(literals.asList())
}

fun BDD.clause(vararg literals: Int): Int {
    return clause_(literals)
}
