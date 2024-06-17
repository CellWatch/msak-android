package edu.gatech.cc.cellwatch.msak.throughput

import kotlinx.datetime.Instant

data class ThroughputUpdate(
    val fromServer: Boolean,
    val stream: Int,
    val time: Instant,
    val measurement: ThroughputMeasurement,
)
