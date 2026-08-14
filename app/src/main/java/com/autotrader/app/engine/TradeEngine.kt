package com.autotrader.app.engine

import android.content.Context
import com.autotrader.app.data.Position
import com.autotrader.app.data.StateStore
import com.autotrader.app.data.Trade
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
data class BotResult(
    val summary: String,
    val trades: List<String>,
    val equity: Double,
    val lastRun: String,
)

object TradeEngine {

    fun money(v: Double): String = String.format(Locale.US, "$%.2f", v)

    fun marketIsOpen(): Boolean {
        val now = ZonedDateTime.now(ZoneId.of("America/New_York"))
        val minutes = now.hour * 60 + now.minute
        return now.dayOfWeek.value in 1..5 && minutes in 570..960
    }

    fun runBot(context: Context): BotResult {
        val tradesLog = mutableListOf<String>()
        var cash = StateStore.cash(context)
        val positions = StateStore.positions(context)

        for (sym in positions.keys.toList()) {
            val s = try {
                DataFetcher.fetchSeries(sym)
            } catch (e: Exception) {
                continue
            }
            val a = Strategy.analyze(s)
            val pos = positions[sym]!!
            val reasons = Strategy.sellReasons(a, pos.avgPrice)
            if (reasons.isNotEmpty()) {
                val proceeds = a.price * pos.shares
                val costs = Strategy.tradeCosts(proceeds)
                cash += proceeds - costs
                positions.remove(sym)
                StateStore.addTrade(
                    context, Trade("SELL", sym, pos.shares, a.price, now(), costs)
                )
                tradesLog.add("SOLD $sym ${pos.shares}sh @ ${money(a.price)} (${reasons.joinToString("; ")})")
            }
        }

        val freeSlots = Strategy.MAX_POSITIONS - positions.size
        if (freeSlots > 0 && cash > 0) {
            val candidates = mutableListOf<Analysis>()
            for (sym in Strategy.WATCHLIST) {
                if (positions.containsKey(sym)) continue
                val s = try {
                    DataFetcher.fetchSeries(sym)
                } catch (e: Exception) {
                    continue
                }
                candidates.add(Strategy.buyCandidate(s) ?: continue)
            }
            if (candidates.isNotEmpty()) {
                candidates.sortByDescending { it.alpha() }
                val perSlot = cash * Strategy.POSITION_SIZE_FRAC / freeSlots.coerceAtMost(candidates.size)
                for (cand in candidates.take(freeSlots)) {
                    val budget = minOf(perSlot, cash * Strategy.POSITION_SIZE_FRAC)
                    if (budget <= 0) break
                    val costs = Strategy.tradeCosts(budget)
                    if (costs > cash) break
                    val shares = ((budget - costs) / cand.price).let { Math.round(it * 10000.0) / 10000.0 }
                    if (shares < 0.01) continue
                    cash -= cand.price * shares + costs
                    positions[cand.symbol] = Position(shares, cand.price)
                    StateStore.addTrade(
                        context, Trade("BUY", cand.symbol, shares, cand.price, now(), costs)
                    )
                    tradesLog.add("BOUGHT ${cand.symbol} ${shares}sh @ ${money(cand.price)} (cost ${money(costs)})")
                }
            }
        }

        StateStore.setCash(context, cash)
        StateStore.savePositions(context, positions)

        var equity = cash
        for ((sym, pos) in positions) {
            equity += try {
                DataFetcher.fetchSeries(sym).closes.last() * pos.shares
            } catch (e: Exception) {
                0.0
            }
        }

        return BotResult(
            summary = "Equity: ${money(equity)}",
            trades = tradesLog,
            equity = equity,
            lastRun = now(),
        )
    }

    fun equity(context: Context): Double {
        var equity = StateStore.cash(context)
        for ((sym, pos) in StateStore.positions(context)) {
            equity += try {
                DataFetcher.fetchSeries(sym).closes.last() * pos.shares
            } catch (e: Exception) {
                pos.avgPrice * pos.shares
            }
        }
        return equity
    }

    fun priceOrAvg(context: Context, symbol: String): Double {
        val pos = StateStore.positions(context)[symbol] ?: return 0.0
        return try {
            DataFetcher.fetchSeries(symbol).closes.last()
        } catch (e: Exception) {
            pos.avgPrice
        }
    }

    fun suggestions(): List<String> {
        val out = mutableListOf<String>()
        for (sym in Strategy.WATCHLIST) {
            val s = try {
                DataFetcher.fetchSeries(sym)
            } catch (e: Exception) {
                continue
            }
            val a = Strategy.analyze(s)
            val cand = Strategy.buyCandidate(s)
            val action = when {
                a.alpha() >= 2.5 && cand != null -> "BUY"
                cand != null -> "BUY (weaker)"
                a.newsScore < -0.4 || (a.rsi ?: 0.0) > 82 -> "AVOID"
                a.sma20 != null && a.price < a.sma20 -> "AVOID"
                else -> "WATCH"
            }
            val reasons = mutableListOf<String>()
            if (a.sma20 != null && a.price > a.sma20) reasons.add(">SMA20")
            if (a.sma50 != null && a.price > a.sma50) reasons.add(">SMA50")
            a.rsi?.let { r -> if (r > 50 && r < 70) reasons.add("RSI ${r.toInt()}") }
            a.momentum20?.let { reasons.add(String.format(Locale.US, "mom %+.1f%%", it)) }
            if (a.newsScore > 0.2) reasons.add(String.format(Locale.US, "news %+.2f", a.newsScore))
            if (a.newsScore < -0.2) reasons.add(String.format(Locale.US, "news %+.2f", a.newsScore))
            out.add("%s %-9s alpha %+.1f %s".format(
                action.padEnd(12), sym, a.alpha(), reasons.joinToString(", ")
            ))
        }
        out.sortWith(compareByDescending<String> { line ->
            Regex("alpha ([+-]?[0-9.]+)").find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: -99.0
        })
        return out
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
}
