package com.autotrader.app.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object News {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val positive = setOf(
        "beat", "exceeds", "growth", "upgrade", "surge", "rally", "gain", "gains",
        "record", "profit", "wins", "partnership", "buyback", "dividend", "strong",
        "raise", "raises", "boosted", "jumped", "soared", "jump", "soar", "optimistic",
        "momentum", "leader", "expand", "expansion", "upbeat", "top", "exceeded",
        "outperform", "guidance", "raised", "positive",
    )

    private val negative = setOf(
        "miss", "misses", "downgrade", "loss", "slump", "plunge", "drop", "decline",
        "cut", "layoff", "lawsuit", "investigation", "warning", "weak", "fail",
        "failed", "lowered", "sank", "tumbled", "fall", "penalty", "profitwarning",
        "downgraded", "sell", "declining", "charges", "probe", "fraud", "recall",
        "suspension", "below", "lower", "gloomy", "pressure", "setback", "uncertainty",
    )

    fun sentiment(symbol: String): Double {
        val url = "https://feeds.finance.yahoo.com/rss/2.0/headline?s=$symbol&region=US&lang=en-US"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12)")
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return 0.0
                val body = resp.body?.string() ?: return 0.0
                val titles = Regex("<title>([^<]+)</title>")
                    .findAll(body)
                    .map { it.groupValues[1].lowercase() }
                    .take(10)
                    .toList()
                var score = 0.0
                for (title in titles) {
                    val words = title.replace(Regex("[^a-z ]"), " ")
                        .split(" ").filter { it.isNotEmpty() }.toSet()
                    val pos = words.count { it in positive }
                    val neg = words.count { it in negative }
                    if (pos + neg > 0) {
                        score += (pos - neg).toDouble() / (pos + neg)
                    }
                }
                (score / titles.size.coerceAtLeast(1)).coerceIn(-1.0, 1.0)
            }
        } catch (e: Exception) {
            0.0
        }
    }
}
