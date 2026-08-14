package com.autotrader.app.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Series(
    val symbol: String,
    val closes: List<Double>,
    val highs: List<Double>,
    val lows: List<Double>,
    val volumes: List<Double>,
    val dates: List<String>,
)

object DataFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun fetchSeries(symbol: String, period: String = "6mo"): Series {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol?range=$period&interval=1d"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12)")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $symbol")
            val root = JSONObject(resp.body?.string() ?: throw RuntimeException("empty body"))
            val result = root.getJSONObject("chart").getJSONArray("result")
            if (result.length() == 0) throw RuntimeException("no data for $symbol")
            val d = result.getJSONObject(0)
            val ts = d.optJSONArray("timestamp")
            val quote = d.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val closes = quote.getJSONArray("close")
            val highs = quote.getJSONArray("high")
            val lows = quote.getJSONArray("low")
            val vols = quote.getJSONArray("volume")
            val n = closes.length()
            val cList = mutableListOf<Double>()
            val hList = mutableListOf<Double>()
            val lList = mutableListOf<Double>()
            val vList = mutableListOf<Double>()
            val dList = mutableListOf<String>()
            for (i in 0 until n) {
                if (closes.isNull(i)) continue
                val c = closes.getDouble(i)
                val h = if (highs.isNull(i)) c else highs.getDouble(i)
                val l = if (lows.isNull(i)) c else lows.getDouble(i)
                val v = if (vols.isNull(i)) 0.0 else vols.getDouble(i)
                val date = dateFmt.format(Date(ts.getLong(i) * 1000))
                cList.add(c); hList.add(h); lList.add(l); vList.add(v); dList.add(date)
            }
            return Series(symbol, cList, hList, lList, vList, dList)
        }
    }
}
