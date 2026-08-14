package com.autotrader.app.engine

object Indicators {

    fun sma(values: List<Double>, window: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (values.size < window) return out
        var sum = values.take(window).sum()
        out[window - 1] = sum / window
        for (i in window until values.size) {
            sum += values[i] - values[i - window]
            out[i] = sum / window
        }
        return out
    }

    fun rsi(values: List<Double>, period: Int = 14): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (values.size < period + 1) return out
        val gains = mutableListOf<Double>()
        val losses = mutableListOf<Double>()
        for (i in 1 until values.size) {
            val diff = values[i] - values[i - 1]
            gains.add(maxOf(diff, 0.0))
            losses.add(maxOf(-diff, 0.0))
        }
        var ag = gains.take(period).average()
        var al = losses.take(period).average()
        out[period] = rsiValue(ag, al)
        for (i in period until gains.size) {
            ag = (ag * (period - 1) + gains[i]) / period
            al = (al * (period - 1) + losses[i]) / period
            out[i + 1] = rsiValue(ag, al)
        }
        return out
    }

    private fun rsiValue(avgGain: Double, avgLoss: Double): Double {
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100 - 100 / (1 + rs)
    }

    fun momentumPct(values: List<Double>, lookback: Int): Double? {
        if (values.size < lookback + 1) return null
        val base = values[values.size - 1 - lookback]
        if (base == 0.0) return null
        return (values.last() - base) / base * 100
    }
}
