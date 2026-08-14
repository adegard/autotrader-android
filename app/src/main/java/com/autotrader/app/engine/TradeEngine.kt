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
                cash += proceeds
                positions.remove(sym)
                StateStore.addTrade(
                    context, Trade("SELL", sym, pos.shares, a.price, now())
                )
                tradesLog.add("SOLD $sym ${pos.shares}sh @ $%.2f (${reasons.joinToString("; ")})".format(a.price))
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
            candidates.sortByDescending { it.momentum20 ?: -999.0 }
            val perSlot = cash * Strategy.POSITION_SIZE_FRAC / freeSlots.coerceAtMost(candidates.size)
            for (cand in candidates.take(freeSlots)) {
                val budget = minOf(perSlot, cash * Strategy.POSITION_SIZE_FRAC)
                if (budget <= 0) break
                val shares = (budget / cand.price).let { Math.round(it * 10000.0) / 10000.0 }
                if (shares < 0.01) continue
                cash -= cand.price * shares
                positions[cand.symbol] = Position(shares, cand.price)
                StateStore.addTrade(
                    context, Trade("BUY", cand.symbol, shares, cand.price, now())
                )
                tradesLog.add("BOUGHT ${cand.symbol} ${shares}sh @ $%.2f".format(cand.price))
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
            summary = "Equity: $%.2f".format(equity),
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

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
}
