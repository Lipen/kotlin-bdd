package com.github.lipen.bdd

import com.soywiz.klock.PerformanceCounter
import com.soywiz.klock.measureTime
import kotlin.math.absoluteValue

fun dichotomic(pigeons: Int, holes: Int = pigeons - 1) {
    val timeStart = PerformanceCounter.reference

    val clauses = generatePhpClauses(pigeons, holes).toList()
    var nvars = clauses.maxOf { clause -> clause.maxOf { lit -> lit.absoluteValue } }
    println("PHP($pigeons, $holes): ${clauses.size} clauses and $nvars variables")

    val umap: MutableMap<Int, Int> = mutableMapOf()
    for (clause in clauses) {
        for (lit in clause) {
            if (lit > 0) {
                umap.computeIfAbsent(lit) { ++nvars }
            }
        }
    }
    println("umap = $umap")
    println("Final nvars = $nvars")

    val bdd = BDD(storageCapacity = 1 shl 20)
    var f = bdd.one

    var stepsTime = 0.0
    var count = 0

    fun step(name: String, allowGC: Boolean = false, block: () -> Unit) {
        count++
        val steptime = measureTime {
            block()
            if (allowGC) {
                if (count % 50 == 0) {
                    bdd.collectGarbage(listOf(f))
                }
            }
        }
        stepsTime += steptime.seconds
        val fsize = bdd.descendants(f).size
        println(
            ("Step #$count ($name) in %.3fms," +
                " bdd.size=${bdd.size}, bdd.realSize=${bdd.realSize}," +
                " f=$f (v=${bdd.getTriplet(f)?.v}), f.size=$fsize," +
                " hits=${bdd.cacheHits}, misses=${bdd.cacheMisses}}")
                .format(steptime.milliseconds)
        )
    }

    fun printBddStats() {
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
        println("-".repeat(42))
    }

    for ((i, clause) in clauses.withIndex()) {
        step("clause ${i + 1}/${clauses.size}") {
            val c = bdd.clause(clause)
            check(!bdd.isZero(f))
            f = bdd.applyAnd(f, c)
        }
    }
    printBddStats()
    check(bdd.isZero(f))
    bdd.collectGarbage(listOf(f))
    f = bdd.one

    for ((i, clause) in clauses.withIndex()) {
        step("uclause ${i + 1}/${clauses.size}") {
            val uclause = clause.map { lit ->
                if (lit > 0) {
                    -umap.getValue(lit)
                } else {
                    lit
                }
            }
            val c = bdd.clause(uclause)
            // println("uclause = $uclause, c = $c")
            check(!bdd.isZero(f))
            f = bdd.applyAnd(f, c)
        }
    }
    printBddStats()

    for ((i, ux) in umap.asIterable().withIndex()) {
        val (u, x) = ux
        check(u > 0)
        check(x > 0)
        step("udef ${i + 1}/${umap.size}", allowGC = true) {
            // (u <=> ~x) is equivalent to (u xor x)
            val c = bdd.applyXor(bdd.mkVar(u), bdd.mkVar(x))
            check(!bdd.isZero(f))
            f = bdd.applyAnd(f, c)
            bdd.collectGarbage(listOf(f))
        }
    }
    printBddStats()

    val totalTime = PerformanceCounter.reference - timeStart
    println("PHP($pigeons, $holes) done in %.2fs (stepsTime=%.2fs)".format(totalTime.seconds, stepsTime))
}

fun main(args: Array<String>) {
    val pigeons = if (args.isNotEmpty()) {
        args[0].toInt()
    } else {
        6  // default
    }
    dichotomic(pigeons = pigeons)
}
