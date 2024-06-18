package com.github.lipen.bdd

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

private fun readClauses(cnfFile: File): List<List<Int>> {
    val result: MutableList<List<Int>> = mutableListOf()
    cnfFile.useLines { lines ->
        for (line in lines) {
            if (line.startsWith("c") || line.startsWith("p")) {
                // skip
                continue
            }
            val clause = line.split(" ").map { it.toInt() }
            check(clause.last() == 0)
            result.add(clause.dropLast(1))
        }
    }
    return result
}

fun schema() {
    println("Running Scheme...")

    val timeStart = TimeSource.Monotonic.markNow()
    val storageCapacity = 1 shl 20
    val bdd = BDD(storageCapacity)
    var f = bdd.one
    var totaltime = Duration.ZERO

    val fileName = "data/schema/sample_1/difficult_cnf_5.txt"
    val clausesRaw = readClauses(File(fileName))
    val numberOfVariables = clausesRaw.maxOf { it.maxOf { it.absoluteValue } }
    val clauses = clausesRaw

    println("CNF file: '$fileName'")
    println("variables: $numberOfVariables")
    println("clausesRaw: ${clausesRaw.size}")
    println("clauses: ${clauses.size}")

    val buckets: List<MutableList<Ref>> = List(clauses.size) { mutableListOf() }
    for (clause in clauses) {
        val v = clause.minOf { it.absoluteValue }
        buckets[v - 1].add(bdd.clause(clause))
    }

    println("-".repeat(42))
    println("Bucket sizes (max = ${buckets.maxOf { it.size }}): ${buckets.map { it.size }}")

    for ((i, bucket) in buckets.withIndex()) {
        println("-".repeat(42))
        val v = i + 1
        println("Preprocessing bucket for v=$v of size ${bucket.size}")
        val g = bdd.cojoinLinear(bucket)
        println("g = $g (${bdd.getTriplet(g)}) of size ${bdd.descendants(g).size}")
        if (bdd.isZero(g)) {
            error("UNSAT on processing")
        }
        bucket.clear()
        bucket.add(g)
    }

    println("-".repeat(42))
    println("Bucket sizes (max = ${buckets.maxOf { it.size }}): ${buckets.map { it.size }}")
    println("Sum BDD sizes (max = ${buckets.maxOf { it.sumOf { bdd.descendants(it).size } }}): " +
        "${buckets.map { it.sumOf { bdd.descendants(it).size } }}"
    )

    for ((i, bucket) in buckets.withIndex()) {
        println("-".repeat(42))
        val v = i + 1
        println("Processing bucket for v=$v of size ${bucket.size}")
        println("BDD sizes: ${bucket.map { bdd.descendants(it).size }.sorted()}")
        val g = bdd.cojoinLinear(bucket)
        println("g = $g (${bdd.getTriplet(g)}) of size ${bdd.descendants(g).size}")
        val h = bdd.exists(g, v)
        println("h = $h (${bdd.getTriplet(h)}) of size ${bdd.descendants(h).size}")
        if (bdd.isOne(h)) {
            // do nothing
        } else if (bdd.isZero(h)) {
            println("UNSAT")
            break
        } else {
            val u = bdd.variable(h)
            check(u > v)
            val j = u - 1
            buckets[j].add(h)
        }
    }

    println("-".repeat(42))
    val totalTime = timeStart.elapsedNow()
    println(
        "Done in %.2fs (totaltime=%.2f)".format(
            totalTime.toDouble(DurationUnit.SECONDS),
            totaltime.toDouble(DurationUnit.SECONDS)
        )
    )
}

fun main() {
    schema()
}
