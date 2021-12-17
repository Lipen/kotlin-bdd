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
    val bdd = BDD(storageCapacity = when {
        pigeons > 25 -> 1 shl 21
        else -> 1 shl 20
    })
    var f = bdd.one
    var totaltime = 0.0

    val useGC = false
    val useCojoinTree = false
    val useProj = true
    val useRelProd = true

    if (useRelProd && !useProj) {
        error("In order to use relProd, you must use Proj")
    }

    fun v(i: Int, j: Int) = (i - 1) * holes + j

    println("Generating and adding PHP clauses...")
    val file = File("data/php/data-php-${pigeons}${if (useProj) "" else "_no-proj"}${if (useGC) "" else "_no-gc"}.txt")
    file.parentFile.mkdirs()
    println("Writing data to: '$file'")
    file.sink().buffer().use {
        with(it) {
            writeUtf8("step name BDD.size f.size steptime totaltime hits misses\n")

            var count = 0

            fun step(name: String, allowGC: Boolean = false, block: () -> Unit): Double {
                count++
                val steptime = measureTime {
                    block()
                    if (allowGC) {
                        if (count % 50 == 0) {
                            bdd.collectGarbage(listOf(f))
                        }
                    }
                }
                val fsize = bdd.descendants(f).size
                println(
                    ("Step #$count ($name) in %.3fms," +
                        " bdd.size=${bdd.size}, bdd.realSize=${bdd.realSize}," +
                        " f=$f (v=${bdd.getTriplet(f)?.v}), f.size=$fsize," +
                        " hits=${bdd.cacheHits}, misses=${bdd.cacheMisses}}")
                        .format(steptime.milliseconds)
                )
                totaltime += steptime.seconds
                val nameSafe = name.replace(" ", "_")
                writeUtf8(
                    "$count $nameSafe ${bdd.realSize} $fsize %.3f %.3f ${bdd.cacheHits} ${bdd.cacheMisses}\n"
                        .format(steptime.seconds, totaltime)
                )
                return steptime.seconds
            }

            for (clause in generatePhpClausesA(pigeons, holes)) {
                step("JoinA", useGC) {
                    val c = bdd.clause(clause)
                    check(!bdd.isZero(f))
                    f = bdd.applyAnd(f, c)
                }
            }

            for (j in 1..holes) {
                var g = bdd.one
                if (useCojoinTree) {
                    step("Cojoin(j=$j)") {
                        val clausesBj = generatePhpClausesBj(pigeons, holes, j)
                        g = bdd.cojoinLinear(clausesBj.map { clause -> bdd.clause(clause) }.asIterable())
                    }
                } else {
                    step("Cojoin(j=$j)") {
                        for (clause in generatePhpClausesBj(pigeons, holes, j)) {
                            val c = bdd.clause(clause)
                            g = bdd.applyAnd(g, c)
                        }
                    }
                }

                if (useRelProd) {
                    step("Join+Proj(j=$j)", useGC) {
                        check(!bdd.isZero(f))
                        val qvars = (1..pigeons).map { i -> v(i, j) }.toSet()
                        f = bdd.relProduct(f, g, qvars)
                    }
                } else {
                    step("JoinB(j=$j)") {
                        check(!bdd.isZero(f))
                        f = bdd.applyAnd(f, g)
                    }

                    if (useProj) {
                        if (j < holes) {
                            // for (i in 1..pigeons) {
                            //     step("Proj(${v(i, j)})") {
                            //         // check(!bdd.isZero(f))
                            //         f = bdd.exists(f, v(i, j))
                            //     }
                            // }
                            // Note: quantification over the set is better that one-by-one
                            val qvars = (1..pigeons).map { i -> v(i, j) }.toSet()
                            step("Proj(j=$j)") {
                                check(!bdd.isZero(f))
                                f = bdd.exists(f, qvars)
                            }
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

    println("-".repeat(42))
    println("bdd.size = ${bdd.size}")
    println("bdd.realSize = ${bdd.realSize}")
    println("bdd.cacheHits = ${bdd.cacheHits}")
    for ((name, hits) in bdd.namedCacheHits) {
        println("  - $name: $hits")
    }
    println("bdd.cacheMisses = ${bdd.cacheMisses}")
    for ((name, misses) in bdd.namedCacheMisses) {
        println("  - $name: $misses")
    }
    println("bdd.maxChain() = ${bdd.maxChain()}")
    println("-".repeat(42))

    val totalTime = PerformanceCounter.reference - timeStart
    println("PHP($pigeons, $holes) done in %.2fs (totaltime=%.2f)".format(totalTime.seconds, totaltime))
}

fun main(args: Array<String>) {
    val pigeons = if (args.isNotEmpty()) {
        args[0].toInt()
    } else {
        40 // default
    }
    php(pigeons = pigeons)
}
