package com.autotrader.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Position(val shares: Double, val avgPrice: Double)

data class Trade(
    val action: String,
    val symbol: String,
    val shares: Double,
    val price: Double,
    val date: String,
)

object StateStore {
    private const val PREFS = "autotrader_state"
    private const val KEY_CASH = "cash"
    private const val KEY_POSITIONS = "positions"
    private const val KEY_HISTORY = "history"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cash(c: Context): Double =
        prefs(c).getString(KEY_CASH, "0")?.replace(',', '.')?.toDoubleOrNull() ?: 0.0

    fun setCash(c: Context, v: Double) =
        prefs(c).edit().putString(KEY_CASH, v.toString()).apply()

    fun deposit(c: Context, amount: Double): Boolean {
        if (amount <= 0) return false
        setCash(c, cash(c) + amount)
        addTrade(c, Trade("DEPOSIT", "-", 0.0, amount, now()))
        return true
    }

    fun positions(c: Context): MutableMap<String, Position> {
        val raw = prefs(c).getString(KEY_POSITIONS, "{}") ?: "{}"
        val obj = JSONObject(raw)
        val out = mutableMapOf<String, Position>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val p = obj.getJSONObject(k)
            out[k] = Position(p.getDouble("shares"), p.getDouble("avg"))
        }
        return out
    }

    fun savePositions(c: Context, positions: Map<String, Position>) {
        val obj = JSONObject()
        for ((k, p) in positions) {
            obj.put(k, JSONObject().put("shares", p.shares).put("avg", p.avgPrice))
        }
        prefs(c).edit().putString(KEY_POSITIONS, obj.toString()).apply()
    }

    fun history(c: Context): List<Trade> {
        val raw = prefs(c).getString(KEY_HISTORY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            Trade(
                t.getString("a"), t.getString("s"),
                t.getDouble("sh"), t.getDouble("p"), t.getString("d"),
            )
        }
    }

    fun addTrade(c: Context, t: Trade) {
        val arr = JSONArray(prefs(c).getString(KEY_HISTORY, "[]") ?: "[]")
        arr.put(
            JSONObject()
                .put("a", t.action).put("s", t.symbol)
                .put("sh", t.shares).put("p", t.price).put("d", t.date)
        )
        prefs(c).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun depositsTotal(c: Context): Double =
        history(c).filter { it.action == "DEPOSIT" }.sumOf { it.price }

    fun reset(c: Context) {
        prefs(c).edit().clear().apply()
    }

    private fun now(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date())
    }
}
