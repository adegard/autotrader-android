package com.autotrader.app.engine

import java.util.Locale

data class Analysis(
    val symbol: String,
    val price: Double,
    val sma20: Double?,
    val sma50: Double?,
    val rsi: Double?,
    val momentum20: Double?,
    val newsScore: Double,
    val date: String,
) {
    fun alpha(): Double {
        var score = 0.0
        val s20 = sma20
        val s50 = sma50
        val m = momentum20
        val r = rsi
        if (s20 != null && s50 != null) {
            if (price > s20) score += 1.0
            if (price > s50) score += 1.0
            if (price > s20 * 1.05) score += 0.5
            if (price < s50) score -= 1.0
        }
        if (m != null) score += (m / 20.0).coerceIn(-1.0, 1.0)
        if (r != null) {
            if (r > 50 && r < 70) score += 0.5
            if (r > 80) score -= 1.0
            if (r < 35) score += 0.3
        }
        val anticipation = if (newsScore > 0 && m != null && m < 8) newsScore * 1.5 else newsScore * 0.8
        score += anticipation
        if (m != null && m > 15 && newsScore > 0.4) score -= 0.8
        return score
    }
}

object Strategy {
    const val MAX_POSITIONS = 5
    const val POSITION_SIZE_FRAC = 0.90
    const val STOP_LOSS_PCT = -8.0
    const val RSI_OVERBOUGHT_SELL = 78.0

    const val COMMISSION_PCT = 0.15
    const val MIN_COMMISSION = 1.0
    const val SLIPPAGE_BPS = 5.0

    val WATCHLIST = listOf(
        "AAPL", "MSFT", "NVDA", "GOOGL", "AMZN", "META", "TSLA", "NFLX",
        "AMD", "AVGO", "INTC", "ORCL", "CRM", "ADBE", "QCOM", "BABA",
        "JPM", "BAC", "DIS", "KO",
        "ENI.MI", "ENEL.MI", "UCG.MI", "ISP.MI", "STLAM.MI", "G.MI",
        "RACE.MI", "LDO.MI", "SAP.DE", "ASML.AS", "MC.PA",
    )

    fun tradeCosts(value: Double): Double {
        val commission = maxOf(value * COMMISSION_PCT / 100, MIN_COMMISSION)
        val slippage = value * SLIPPAGE_BPS / 10000
        return commission + slippage
    }

    fun analyze(s: Series): Analysis {
        val rsiVal = Indicators.rsi(s.closes, 14).last()
        val s20 = Indicators.sma(s.closes, 20).last()
        val s50 = Indicators.sma(s.closes, 50).last()
        return Analysis(
            symbol = s.symbol,
            price = s.closes.last(),
            sma20 = s20,
            sma50 = s50,
            rsi = rsiVal,
            momentum20 = Indicators.momentumPct(s.closes, 20),
            newsScore = News.sentiment(s.symbol),
            date = s.dates.last(),
        )
    }

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
        if (a.newsScore < -0.5) reasons.add("negative news")
        return reasons
    }
}
