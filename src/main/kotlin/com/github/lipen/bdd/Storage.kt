package com.github.lipen.bdd

internal class Storage(capacity: Int) {
    // internal val dataOccupied = java.util.BitSet(capacity)
    internal val dataOccupied = BooleanArray(capacity)
    internal val dataVar = IntArray(capacity)
    internal val dataLow = IntArray(capacity)
    internal val dataHigh = IntArray(capacity)
    internal val dataNext = IntArray(capacity)

    var lastIndex: Int = 0
        private set
    var realSize: Int = 0
        private set

    // Invariant: variable(0) = low(0) = high(0) = next(0) = 0
    fun variable(index: Int): Int = dataVar[index]
    fun low(index: Int): Int = dataLow[index]
    fun high(index: Int): Int = dataHigh[index]
    fun next(index: Int): Int = dataNext[index]

    private fun getFreeIndex(): Int {
        return ++lastIndex
        // return (1..lastIndex).firstOrNull { !dataOccupied[it] } ?: ++lastIndex
    }

    fun add(v: Int, low: Int, high: Int, next: Int = 0): Int {
        require(v > 0)
        require(low != 0)
        require(high != 0)
        val index = getFreeIndex()
        realSize++
        dataOccupied[index] = true
        dataVar[index] = v
        dataLow[index] = low
        dataHigh[index] = high
        dataNext[index] = next
        return index
    }

    fun alloc(index: Int) {
        require(index > 0)
        if (index > lastIndex) {
            lastIndex = index
        }
        realSize++
        dataOccupied[index] = true
    }

    fun drop(index: Int) {
        require(index > 0)
        realSize--
        dataOccupied[index] = false
    }

    fun setNext(index: Int, next: Int) {
        require(index > 0)
        dataNext[index] = next
    }
}
