package com.github.lipen.bdd

import kotlin.math.absoluteValue

/**
 * [Cantor pairing function](https://en.wikipedia.org/wiki/Pairing_function#Cantor_pairing_function)
 */
internal fun hash2(a: Int, b: Int): Int {
    require(a >= 0)
    require(b >= 0)
    return (a + 1) * (a + b + 1) / 2 + a
}

internal fun hash3(a: Int, b: Int, c: Int): Int {
    require(a >= 0)
    require(b >= 0)
    require(c >= 0)
    return hash2(hash2(a, b).absoluteValue, c).absoluteValue
}
