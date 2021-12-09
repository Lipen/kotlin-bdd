package com.github.lipen.bdd

import com.soywiz.klock.PerformanceCounter
import com.soywiz.klock.measureTime
import okio.buffer
import okio.sink
import java.io.File

private fun generatePhpClausesA(pigeons: Int, holes: Int): Sequence<List<Int>> = sequence {
    fun v(i: Int, j: Int) = (i - 1) * holes + j

    // A: p clauses
    for (i in 1..pigeons)
        yield((1..holes).map { j -> v(i, j) })
}

private fun generatePhpClausesBj(pigeons: Int, holes: Int, j: Int): Sequence<List<Int>> = sequence {
    fun v(i: Int, j: Int) = (i - 1) * holes + j

    // B[j] clauses
    for (i in 1..pigeons) //(p-1) * p / 2
        for (k in i + 1..pigeons)
            yield(listOf(-v(i, j), -v(k, j)))
}

private fun generatePhpClausesB(pigeons: Int, holes: Int): Sequence<List<Int>> = sequence {
    // B clauses
    for (j in 1..holes) //h *
        yieldAll(generatePhpClausesBj(pigeons, holes, j))
}

private fun generatePhpClauses(pigeons: Int, holes: Int): Sequence<List<Int>> = sequence {
    require(pigeons > 0)
    require(holes > 0)
    // A: p clauses
    yieldAll(generatePhpClausesA(pigeons, holes))
    // B clauses
    yieldAll(generatePhpClausesB(pigeons, holes))
}

fun php(pigeons: Int, holes: Int = pigeons - 1) {
    require(pigeons > 0)
    require(holes > 0)

    println("Running PHP(p = $pigeons, h = $holes})...")

    val timeStart = PerformanceCounter.reference
    val bdd = BDD()
    var f = bdd.one
    var totaltime = 0.0

    fun v(i: Int, j: Int) = (i - 1) * holes + j

    println("Generating and adding PHP clauses...")
    val file = File("data-php-${pigeons}.txt")
    println("Writing data to: '$file'")
    file.sink().buffer().use {
        with(it) {
            writeUtf8("step name BDD.size f.size steptime totaltime hits misses\n")

            var count = 0

            fun step(name: String, init: () -> Unit) {
                val steptime = measureTime {
                    init()
                    if ((count + 1) % 50 == 0) {
                        bdd.collectGarbage(listOf(f))
                    }
                }
                val fsize = bdd.descendants(f).size
                println(
                    "Step #${count + 1} ($name) in %.3fs, BDD.size = ${bdd.size} (real size = ${bdd.realSize()}), f (size=$fsize) = $f, hits = ${bdd.cacheHits}, misses = ${bdd.cacheMisses}"
                        .format(steptime.seconds)
                )
                totaltime += steptime.seconds
                val nameSafe = name.replace(" ", "_")
                writeUtf8(
                    "${count + 1} $nameSafe ${bdd.realSize()} $fsize %.3f %.3f ${bdd.cacheHits} ${bdd.cacheMisses}\n"
                        .format(steptime.seconds, totaltime)
                )
                count++
            }

            for (clause in generatePhpClausesA(pigeons, holes)) {
                step("JoinA") {
                    val c = bdd.clause(clause)
                    f = bdd.applyAnd(f, c)
                }
            }

            for (j in 1..holes) {
                for (clause in generatePhpClausesBj(pigeons, holes, j)) {
                    step("JoinB") {
                        val c = bdd.clause(clause)
                        f = bdd.applyAnd(f, c)
                    }
                }
                if (j < holes) {
                    for (i in 1..pigeons) {
                        step("Proj") {
                            f = bdd.exists(f, v(i, j))
                        }
                    }
                }
            }
        }
    }

    println("final f = $f")
    check(bdd.isZero(f)) {
        "Formula must be Zero (UNSAT) after adding all PHP clauses"
    }
    val totalTime = PerformanceCounter.reference - timeStart
    println("PHP($pigeons, $holes) done in %.2fs (totaltime=%.2f)".format(totalTime.seconds, totaltime))
}

fun main(args: Array<String>) {
    val pigeons = if (args.isNotEmpty()) {
        args[0].toInt()
    } else {
        10 // default
    }
    php(pigeons = pigeons)
}
