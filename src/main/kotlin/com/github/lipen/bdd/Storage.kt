package com.github.lipen.bdd

import kotlin.math.min

// TODO rename `capacity` to `size`

internal class Storage(capacity: Int) {
    // Note: 0th cell (index=0) is a sentry.
    private val dataOccupied = java.util.BitSet(capacity)
    private val dataVar = IntArray(capacity)
    private val dataLow = IntArray(capacity)
    private val dataHigh = IntArray(capacity)
    private val dataNext = IntArray(capacity)

    /**
     * Index of the first *possibly* free (non-occupied) cell.
     */
    private var minFree: Int = 1

    /**
     * Index of the last occupied cell.
     */
    var lastIndex: Int = 0
        private set

    /**
     * Number of occupied cells.
     */
    var realSize: Int = 0
        private set

    fun isOccupied(index: Int): Boolean = dataOccupied[index]

    // Invariant: variable(0) = low(0) = high(0) = next(0) = 0
    fun variable(index: Int): Int = dataVar[index]
    fun low(index: Int): Int = dataLow[index]
    fun high(index: Int): Int = dataHigh[index]
    fun next(index: Int): Int = dataNext[index]

    /**
     * Allocate a new cell, occupy it and return its index.
     */
    internal fun alloc(): Int {
        val index = (minFree..lastIndex).firstOrNull { !dataOccupied[it] } ?: ++lastIndex
        realSize++
        dataOccupied[index] = true
        minFree = index + 1
        return index
    }

    /**
     * Add a new node into [Storage].
     *
     * Returns the index of the allocated cell.
     */
    fun add(v: Int, low: Int, high: Int): Int {
        require(v > 0)
        require(low != 0)
        require(high != 0)
        val index = alloc()
        dataVar[index] = v
        dataLow[index] = low
        dataHigh[index] = high
        dataNext[index] = 0
        return index
    }

    /**
     * Deallocate a cell with a given [index].
     */
    fun drop(index: Int) {
        require(index > 0)
        realSize--
        dataOccupied[index] = false
        minFree = min(minFree, index)
    }

    fun setNext(index: Int, next: Int) {
        require(index > 0)
        require(next >= 0)
        dataNext[index] = next
    }
}
