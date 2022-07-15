package com.github.lipen.bdd

import com.soywiz.klock.PerformanceCounter
import com.soywiz.klock.measureTimeWithResult
import okio.buffer
import okio.sink
import java.io.File
import kotlin.math.absoluteValue

private val logger = mu.KotlinLogging.logger {}

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

fun geffe() {
    println("Running Geffe...")

    val timeStart = PerformanceCounter.reference
    val bdd = BDD(1 shl 24)
    var f = bdd.one
    var totaltime = 0.0
    // val cnfFile = File("data/geffe/geffe_100.cnf")
    val cnfFile = File("data/geffe/geffe_30.cnf")
    // val cnfFile = File("data/geffe/geffe_30_out1.cnf")
    // val cnfFile = File("data/A5_1/a5-1_547-0001101001100100011100111000110010101011110111001101101000000101.cnf")
    val clausesRaw = readClauses(cnfFile).sortedBy { it.minOf { it.absoluteValue } }
    val numberOfVariables = clausesRaw.asSequence().flatMap { it.asSequence() }.maxOf { it.absoluteValue }
    // val numberOfOutputs = 100
    val numberOfOutputs = 30
    val output = (1..10).map { -it } + (11..numberOfOutputs).toList()
    val clauses = clausesRaw +
        output.map { lit ->
            listOf(
                (numberOfVariables - numberOfOutputs + lit.absoluteValue)
                    .let { x -> if (lit < 0) -x else x }
            )
        }
    // val clauses = clausesRaw

    println("CNF file: '$cnfFile'")
    println("variables: $numberOfVariables")
    println("clausesRaw: ${clausesRaw.size}")
    println("clauses: ${clauses.size}")

    // data class Node(val node: Int) : Comparable<Node> {
    //     val size: Int = bdd.descendants(node).size
    //
    //     override fun compareTo(other: Node): Int {
    //         return size compareTo other.size
    //     }
    // }

    // val queue = PriorityQueue(clauses.map { clause -> Node(bdd.clause(clause)) })

    val useGC = false
    val useProj = false

    // val file = File("data/A5_1/data-a5.txt")
    val file = File("data/geffe/data-geffe.txt")
    file.parentFile.mkdirs()
    println("Writing data to: '$file'")
    file.sink().buffer().use {
        with(it) {
            writeUtf8("step name BDD.size f.size steptime totaltime hits misses\n")

            var count = 0

            fun <T> step(name: String, allowGC: Boolean = false, block: () -> T): T {
                count++
                val (result, steptime) = measureTimeWithResult {
                    block().also {
                        if (allowGC) {
                            if (count % 500 == 0 || bdd.realSize > 10000) {
                                bdd.collectGarbage(listOf(f))
                            }
                        }
                    }
                }
                val fsize = bdd.descendants(f).size
                println(
                    ("Step #$count $name in %.3fs, bdd.size=${bdd.size}, bdd.realSize=${bdd.realSize}," +
                        " f=$f (f.v=${bdd.getTriplet(f)?.v}), f.size=$fsize," +
                        " hits=${bdd.cacheHits}, misses=${bdd.cacheMisses}")
                        .format(steptime.seconds)
                )
                totaltime += steptime.seconds
                val nameSafe = name.replace(" ", "_")
                writeUtf8(
                    "$count $nameSafe ${bdd.realSize} $fsize %.3f %.3f ${bdd.cacheHits} ${bdd.cacheMisses}\n"
                        .format(steptime.seconds, totaltime)
                )
                return result
            }

            // for (clause in clauses) {
            //     step("Join", useGC) {
            //         val c = bdd.clause(clause)
            //         f = bdd.applyAnd(f, c)
            //     }
            // }

            val usedClauses = mutableSetOf<List<Int>>()

            for (v in 65..numberOfVariables) {
                println("-".repeat(42))
                val cs = (clauses - usedClauses).filter { clause -> v in clause || -v in clause }
                usedClauses.addAll(cs)
                println("v = $v, cs = ${cs.size}")
                val g = step("Cojoin") {
                    bdd.cojoinLinear(bdd.axioms(cs))
                }
                step("Join") {
                    f = bdd.relProduct(f, g, setOf(v))
                }
            }

            for (v in 1..64) {
                println("-".repeat(42))
                val cs = (clauses - usedClauses).filter { clause -> v in clause || -v in clause }
                usedClauses.addAll(cs)
                println("v = $v, cs = ${cs.size}")
                step("Join") {
                    for (clause in cs) {
                        val c = bdd.clause(clause)
                        f = bdd.applyAnd(f, c)
                    }
                }
            }

            println("left clauses: ${(clauses - usedClauses).size}")
            for (c in clauses - usedClauses) {
                println("  - $c")
            }

            // val sortedClauses = clauses.map { clause -> clause.sortedBy { lit -> lit.absoluteValue } }
            // val groupedClauses = sortedClauses.groupBy { clause -> clause.first().absoluteValue }

            // var h = bdd.one

            // fun go(v: Int) {
            //     println("-".repeat(42))
            //     val cs = groupedClauses[v] ?: emptyList()
            //     println("v = $v, cs = ${cs.size}, left = ${(v+1..numberOfVariables).sumOf { u -> groupedClauses[v]?.size ?: 0 }}")
            //     var g = bdd.one
            //     step("Cojoin(v = $v)") {
            //         for (clause in cs) {
            //             val c = bdd.clause(clause)
            //             g = bdd.applyAnd(g, c)
            //         }
            //     }
            //     step("Interjoin(v = $v)") {
            //         h = bdd.applyAnd(h, g)
            //     }
            //     println("v = $v, h = $h of size ${bdd.descendants(h).size}")
            //     if (v % 2 == 0) {
            //         step("Join+Proj(v = $v)", useGC) {
            //             f = bdd.relProduct(f, h, (65..v).toSet())
            //             // f = bdd.relProduct(f, h, (v..numberOfVariables).toSet())
            //         }
            //         h = bdd.one
            //     }
            // }
            //
            // for (v in 65..numberOfVariables) {
            //     go(v)
            // }
            // for (v in 1..64) {
            //     go(v)
            // }

            // val sortedClauses = clauses.map { clause -> clause.sortedBy { lit -> lit.absoluteValue } }
            // val groupedClauses = sortedClauses.groupBy { clause -> clause.first().absoluteValue }
            // val clausesForLit = (1..numberOfVariables).associateWith { v ->
            //     sortedClauses.filter { clause -> v in clause || -v in clause }
            // }
            // val used = BooleanArray(clauses.size)
            //
            // for (v in 65..numberOfVariables) {
            //     println("v = $v")
            //     val connectedAll = sortedClauses.filter { clause ->
            //         v in clause || -v in clause
            //     }
            //     println("connectedAll = ${connectedAll.size}")
            //     val connectedUnused = connectedAll.filter { clause ->
            //         !used[sortedClauses.indexOf(clause)]
            //     }
            //     println("connectedUnused = ${connectedUnused.size}")
            //     if (connectedUnused.isEmpty()) continue
            //
            //     // var ok = true
            //     // for (j in 1..64) {
            //     //     for (c in connectedUnused) {
            //     //         if (j in c) {
            //     //             ok = false
            //     //             break
            //     //         }
            //     //     }
            //     //     if (!ok) break
            //     // }
            //     // if (!ok) continue
            //
            //     val axioms = connectedUnused.map { clause ->
            //         used[sortedClauses.indexOf(clause)] = true
            //         bdd.clause(clause)
            //     }
            //     val h = step("cojoin(v = $v)") {
            //         bdd.cojoinTree(axioms)
            //     }
            //     println("cojoined g=@$h of size ${bdd.descendants(h).size} = ${bdd.getNode(h)}")
            //
            //     step("join(v = $v)", useGC) {
            //         // println("f = @$f of size ${bdd.descendants(f).size} = ${bdd.getNode(f)}")
            //         f = bdd.applyAnd(f, h)
            //     }
            //
            //     step("proj(v = $v)", useGC) {
            //         f = bdd.exists(f, v)
            //     }
            // }

            // for (v in 65..numberOfVariables) {
            //     step("Cojoin") {
            //         g = bdd.one
            //         val vClauses = groupedClauses[v] ?: emptyList()
            //         for (clause in vClauses) {
            //             i++
            //             // println("Clause #$i/${vClauses.size}/${clauses.size} for v=$v of size ${clause.size}")
            //             // step("Cojoin") {
            //             val c = bdd.clause(clause)
            //             g = bdd.applyAnd(g!!, c)
            //         }
            //     }
            //     step("Join") {
            //         // logger.info {
            //         //     "Joining @$f (v=${bdd.getNode(f)?.v}, size=${bdd.descendants(f).size})" +
            //         //         " and @$g (v=${bdd.getNode(g!!)?.v}, size=${bdd.descendants(g!!).size})"
            //         // }
            //         f = bdd.applyAnd(f, g!!)
            //     }
            //     // step("Proj") {
            //     //     for (j in 65..numberOfVariables) {
            //     //         f = bdd.exists(f, j)
            //     //     }
            //     // }
            // }
            //
            // println("=".repeat(42))
            //
            // for (v in 1..64) {
            //     g = bdd.one
            //     val vClauses = groupedClauses[v] ?: emptyList()
            //     for (clause in vClauses) {
            //         i++
            //         // println("Clause #$i/${vClauses.size}/${clauses.size} for v=$v of size ${clause.size}")
            //         step("Cojoin") {
            //             val c = bdd.clause(clause)
            //             g = bdd.applyAnd(g!!, c)
            //         }
            //     }
            // }

            // var lastVar = 65
            //
            // for ((i, clause) in clauses.withIndex()) {
            //     val v = clause.minOf { it.absoluteValue }
            //
            //     if (v <= 64) {
            //         continue
            //     }
            //
            //     println("#${i + 1}/${clauses.size}, v = $v, clause = $clause")
            //
            //     if (v > lastVar) {
            //         for (k in lastVar until v) {
            //             step("Proj($k)") {
            //                 f = bdd.exists(f, k)
            //             }
            //         }
            //         lastVar = v
            //     }
            //
            //     step("Join") {
            //         val c = bdd.clause(clause)
            //         f = bdd.applyAnd(f, c)
            //     }
            // }
            //
            // step("Proj($lastVar)") {
            //     f = bdd.exists(f, lastVar)
            // }
            //
            // for ((i, clause) in clauses.withIndex()) {
            //     val v = clause.minOf { it.absoluteValue }
            //
            //     if (v > 64) {
            //         continue
            //     }
            //
            //     println("#${i + 1}/${clauses.size}, v = $v, clause = $clause")
            //
            //     step("Join") {
            //         val c = bdd.clause(clause)
            //         f = bdd.applyAnd(f, c)
            //     }
            // }

            // while (queue.size > 1) {
            //     println("- queue.size=${queue.size}, sum sizes=${queue.sumOf { it.size }}")
            //     val a = queue.remove()
            //     val b = queue.remove()
            //     step("Co-Join") {
            //         val r = Node(bdd.applyAnd(a.node, b.node))
            //         println("> a=$a (a.size=${a.size}), b=$b (b.size=${b.size}), r=$r (r.size=${r.size})")
            //         queue.add(r)
            //     }
            // }

        }
    }

    println("-".repeat(42))
    println("final f = $f of size ${bdd.descendants(f).size} = ${bdd.getTriplet(f)}")

    println("-".repeat(42))
    println("bdd.size = ${bdd.size}")
    println("bdd.realSize() = ${bdd.realSize}")
    // println("bdd.maxChain() = ${bdd.maxChain()}")
    // println("bdd.chains() = ${bdd.chains()}")
    println("-".repeat(42))

    val oneSat = bdd.oneSat(f, n = numberOfVariables)
    if (oneSat.isEmpty()) {
        println("UNSAT")
    } else {
        val oneSatString = oneSat.joinToString("") {
            when (it) {
                true -> "1"
                false -> "0"
                null -> "x"
            }
        }
        println("bdd.oneSat(f) = ${oneSatString.take(64)}")
    }

    println("-".repeat(42))
    val totalTime = PerformanceCounter.reference - timeStart
    println("Done in %.2fs (totaltime=%.2f)".format(totalTime.seconds, totaltime))
}

fun main() {
    geffe()
}
