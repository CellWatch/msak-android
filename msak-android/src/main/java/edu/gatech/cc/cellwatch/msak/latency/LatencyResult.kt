package edu.gatech.cc.cellwatch.msak.latency

data class LatencyResult(
    val ID: String,
    val StartTime: String,
    val RoundTrips: List<LatencyRoundTrip>,
    val PacketsSent: Int,
    val PacketsReceived: Int,
)
