package com.autotrader.app.engine

import java.util.Locale

data class Analysis(
    val symbol: String,
    val price: Double,
    val sma20: Double?,
    val sma50: Double?,
    val rsi: Double?,
    val momentum20: Double?,
    val date: String,
)

object Strategy {
    const val MAX_POSITIONS = 5
    const val POSITION_SIZE_FRAC = 0.90
    const val STOP_LOSS_PCT = -8.0
    const val RSI_OVERBOUGHT_SELL = 78.0

    val WATCHLIST = listOf(
        "AAPL", "MSFT", "NVDA", "GOOGL", "AMZN", "META", "TSLA", "NFLX",
        "AMD", "AVGO", "INTC", "ORCL", "CRM", "ADBE", "QCOM", "BABA",
        "JPM", "BAC", "DIS", "KO",
    )

    fun analyze(s: Series): Analysis = Analysis(
        symbol = s.symbol,
        price = s.closes.last(),
        sma20 = Indicators.sma(s.closes, 20).last(),
        sma50 = Indicators.sma(s.closes, 50).last(),
        rsi = Indicators.rsi(s.closes, 14).last(),
        momentum20 = Indicators.momentumPct(s.closes, 20),
        date = s.dates.last(),
    )

    fun buyCandidate(s: Series): Analysis? {
        val a = analyze(s)
        val s20 = a.sma20 ?: return null
        val s50 = a.sma50 ?: return null
        val r = a.rsi ?: return null
        val m = a.momentum20 ?: return null
        if (a.price > s20 && a.price > s50 && r < RSI_OVERBOUGHT_SELL && m > 0) return a
        return null
    }

    fun sellReasons(a: Analysis, avgPrice: Double): List<String> {
        val reasons = mutableListOf<String>()
        val s20 = a.sma20 ?: return reasons
        val r = a.rsi ?: return reasons
        val pnl = (a.price / avgPrice - 1) * 100
        if (r > RSI_OVERBOUGHT_SELL) reasons.add(String.format(Locale.US, "RSI %.0f overbought", r))
        if (a.price < s20) reasons.add("broke SMA20")
        if (pnl <= STOP_LOSS_PCT) reasons.add(String.format(Locale.US, "stop-loss %.1f%%", pnl))
        return reasons
    }
}
