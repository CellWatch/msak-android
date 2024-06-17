package edu.gatech.cc.cellwatch.msak.latency

data class LatencyMessage(
    val Type: String, // "c2s" or "s2c"
    val ID: String,
    val Seq: Int,
    val LastRTT: Int?,
)
