package com.autotrader.app.engine

import android.content.Context
import android.content.SharedPreferences
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SaxoAccount(val accountKey: String, val currency: String, val name: String)

class SaxoClient(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("autotrader_saxo", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    var env: String
        get() = prefs.getString("env", "SIM")!!
        set(v) = prefs.edit().putString("env", v).apply()

    var appKey: String
        get() = prefs.getString("appKey", "")!!
        set(v) = prefs.edit().putString("appKey", v).apply()

    var appSecret: String
        get() = prefs.getString("appSecret", "")!!
        set(v) = prefs.edit().putString("appSecret", v).apply()

    var accessToken: String
        get() = prefs.getString("accessToken", "")!!
        set(v) = prefs.edit().putString("accessToken", v).apply()

    var refreshToken: String
        get() = prefs.getString("refreshToken", "")!!
        set(v) = prefs.edit().putString("refreshToken", v).apply()

    var tokenExpiry: Long
        get() = prefs.getLong("tokenExpiry", 0)
        set(v) = prefs.edit().putLong("tokenExpiry", v).apply()

    private val base: String
        get() = if (env == "LIVE") "https://gateway.saxobank.com" else "https://gateway.saxobank.com/sim"

    val isAuthenticated: Boolean
        get() = accessToken.isNotBlank()

    val authUrl: String
        get() {
            val host = if (env == "LIVE") "https://login.saxobank.com/openapi/authorize"
            else "https://sim.logon.saxo/openapi/authorize"
            return "$host?client_id=${appKey}&redirect_uri=autotrader://callback&response_type=code&state=saxoauth"
        }

    fun exchangeCode(code: String) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", appKey)
            .add("client_secret", appSecret)
            .add("redirect_uri", "autotrader://callback")
            .build()
        val json = JSONObject(call("$base/openapi/auth/token", body, authed = false))
        accessToken = json.getString("access_token")
        refreshToken = json.optString("refresh_token", refreshToken)
        tokenExpiry = System.currentTimeMillis() + json.optLong("expires_in", 1800) * 1000
    }

    @Synchronized
    fun ensureToken() {
        if (accessToken.isBlank()) throw IllegalStateException("Not authenticated. Open the Saxo login first.")
        if (System.currentTimeMillis() > tokenExpiry - 30000) refreshAccess()
    }

    private fun refreshAccess() {
        if (refreshToken.isBlank()) throw IllegalStateException("No refresh token. Reconnect.")
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", appKey)
            .add("client_secret", appSecret)
            .build()
        val json = JSONObject(call("$base/openapi/auth/token", body, authed = false))
        accessToken = json.getString("access_token")
        refreshToken = json.optString("refresh_token", refreshToken)
        tokenExpiry = System.currentTimeMillis() + json.optLong("expires_in", 1800) * 1000
    }

    fun getClientKey(): String {
        ensureToken()
        return JSONObject(call("$base/openapi/port/v1/clients/me", authed = true)).getString("ClientKey")
    }

    fun getAccounts(): List<SaxoAccount> {
        val key = getClientKey()
        val resp = call("$base/openapi/port/v1/accounts?ClientKey=$key", authed = true)
        val arr = JSONObject(resp).getJSONArray("Data")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SaxoAccount(o.getString("AccountKey"), o.optString("Currency", "EUR"), o.optString("DisplayName", ""))
        }
    }

    fun getBalance(accountKey: String): String {
        val key = getClientKey()
        val resp = call("$base/openapi/port/v1/balances?ClientKey=$key&FieldGroups=TotalAndCash", authed = true)
        val arr = JSONObject(resp).getJSONArray("Data")
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            if (b.optString("AccountKey", "") == accountKey) {
                val tv = b.optDouble("TotalValue", 0.0)
                val cash = b.optDouble("CashOnDeposit", 0.0)
                return String.format(java.util.Locale.US, "Balance: %.2f   Cash: %.2f", tv, cash)
            }
        }
        return "No balance data for this account."
    }

    fun getPositions(accountKey: String): String {
        val key = getClientKey()
        val resp = call(
            "$base/openapi/port/v1/netpositions?ClientKey=$key&AccountKey=$accountKey&FieldGroups=DisplayAndFormat,PositionBase",
            authed = true
        )
        val arr = JSONObject(resp).getJSONArray("Data")
        if (arr.length() == 0) return "No open positions."
        val sb = StringBuilder("POSITIONS\n")
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val name = p.optJSONObject("DisplayAndFormat")?.optString("Name") ?: ""
            val amount = p.optDouble("Amount", 0.0)
            val price = p.optDouble("CurrentPrice", 0.0)
            sb.append("%s %s @ %.2f\n".format(name, "%.4f".format(amount), price))
        }
        return sb.toString().trimEnd()
    }

    fun searchUic(symbol: String): Pair<String, Int>? {
        ensureToken()
        val resp = call("$base/openapi/ref/v1/instruments?Keywords=$symbol&AssetTypes=Stock", authed = true)
        val arr = JSONObject(resp).optJSONArray("Data") ?: return null
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        return Pair(o.optString("SummaryType", ""), o.getInt("Uic"))
    }

    fun placeOrder(accountKey: String, uic: Int, amount: Int, buySell: String): String {
        ensureToken()
        val payload = JSONObject()
            .put("AccountKey", accountKey)
            .put("AssetType", "Stock")
            .put("BuySell", buySell)
            .put("Amount", amount)
            .put("OrderType", "Market")
            .put("Uic", uic)
            .put("ManualOrder", true)
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val resp = call("$base/openapi/trade/v2/orders", body, authed = true)
        val json = JSONObject(resp)
        val orderId = json.optJSONArray("OrderId")
        return if (orderId != null) "Order placed: ${orderId.getString(0)}" else "Order response: $resp"
    }

    private fun call(url: String, body: RequestBody? = null, authed: Boolean): String {
        val builder = if (body == null) Request.Builder().url(url).get()
        else Request.Builder().url(url).post(body)
        if (authed) builder.header("Authorization", "Bearer $accessToken")
        return client.newCall(builder.build()).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}: $s")
            s
        }
    }
}
