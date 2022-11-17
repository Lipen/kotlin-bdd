package com.github.lipen.bdd

internal interface Cache {
    val hits: Int
    val misses: Int

    fun clear()
}
