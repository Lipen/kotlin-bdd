package com.github.lipen.bdd

import com.soywiz.klock.PerformanceCounter
import kotlin.math.max
import kotlin.math.min

private const val GC = true
private const val QUIET = true

private fun label_of_position(n: Int, i: Int, j: Int): Int {
    require(i in 1..n)
    require(j in 1..n)
    return (n * (i - 1)) + (j - 1) + 1
}

private fun BDD.queens_square(n: Int, i: Int, j: Int): Ref {
    var out = one

    for (row in (1..n).reversed()) {
        val diff = max(row, i) - min(row, i)
        if (diff == 0) {
            for (col in (1..n).reversed()) {
                val label = label_of_position(n, row, col)
                val v = mkVar(label)
                val node = if (col == j) v else -v
                out = applyAnd(out, node)
                if (!QUIET) println(
                    "row/col = $row/$col: out = $out" +
                        " (size=${size(out)}, count=${count(out, n * n)})"
                )
            }
        } else {
            if (j + diff <= n) {
                val label = label_of_position(n, row, j + diff)
                val node = mkVar(-label)
                out = applyAnd(out, node)
                if (!QUIET) println(
                    "row=$row, j+diff=${j + diff}, out = $out" +
                        " (size=${size(out)}, count=${count(out, n * n)})"
                )
            }

            run {
                val label = label_of_position(n, row, j)
                val node = mkVar(-label)
                out = applyAnd(out, node)
                if (!QUIET) println(
                    "row=$row, j=$j, out = $out" +
                        " (size=${size(out)}, count=${count(out, n * n)})"
                )
            }

            if (diff < j) {
                val label = label_of_position(n, row, j - diff)
                val node = mkVar(-label)
                out = applyAnd(out, node)
                if (!QUIET) println(
                    "row=$row, j-diff=${j - diff}, out = $out" +
                        " (size=${size(out)}, count=${count(out, n * n)})"
                )
            }
        }
    }

    return out
}

private fun BDD.queens_row(n: Int, row: Int): Ref {
    var out = zero
    for (j in 1..n) {
        out = applyOr(out, queens_square(n, row, j))
        if (!QUIET) println(
            "Column $j/$n: out = $out" +
                " (size=${size(out)}, count=${count(out, n * n)})"
        )
    }
    return out
}

private fun BDD.queens_board(n: Int): Ref {
    var out = one
    for (i in 1..n) {
        out = applyAnd(out, queens_row(n, i))
        if (!QUIET) println(
            "Row $i/$n: out = $out" +
                " (size=${size(out)}, count=${count(out, n * n)})"
        )
        if (GC) {
            val timeStartGC = PerformanceCounter.reference
            collectGarbage(listOf(out))
            val timeGC = PerformanceCounter.reference - timeStartGC
            println("[$i/$n], GC in %.2fs: bdd.size = $size, bdd.realSize = $realSize".format(timeGC.seconds))
        }
    }
    return out
}

fun queens() {
    val n = 12

    val timeStart = PerformanceCounter.reference
    val bdd = BDD(storageBits = 24)

    println("Calculating BDD for a board with N=$n queens...")
    val timeStartBoard = PerformanceCounter.reference
    val board = bdd.queens_board(n)
    val timeBoard = PerformanceCounter.reference - timeStartBoard
    println("Built BDD for a board with N=$n queens in %.2fs".format(timeBoard.seconds))
    println()
    println("n = $n, board = $board (size = ${bdd.size(board)}, count = ${bdd.count(board, n * n)})")
    println()

    println("bdd.size = ${bdd.size}")
    println("bdd.realSize = ${bdd.realSize}")
    println()
    val timeTotal = PerformanceCounter.reference - timeStart
    println("All done in %.2fs".format(timeTotal.seconds))
}

fun main() {
    queens()
}
