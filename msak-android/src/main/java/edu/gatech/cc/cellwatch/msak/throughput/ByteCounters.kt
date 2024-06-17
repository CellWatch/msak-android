package edu.gatech.cc.cellwatch.msak.throughput

import kotlinx.serialization.Serializable

@Serializable
data class ByteCounters(
    val BytesSent: Long,
    val BytesReceived: Long,
)
