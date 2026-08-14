package com.autotrader.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autotrader.app.engine.SaxoAccount
import com.autotrader.app.engine.SaxoClient

class SaxoActivity : AppCompatActivity() {

    private lateinit var saxo: SaxoClient
    private lateinit var spEnv: Spinner
    private lateinit var etAppKey: EditText
    private lateinit var etAppSecret: EditText
    private lateinit var etSymbol: EditText
    private lateinit var etQty: EditText
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saxo)

        saxo = SaxoClient(this)
        spEnv = findViewById(R.id.spEnv)
        etAppKey = findViewById(R.id.etAppKey)
        etAppSecret = findViewById(R.id.etAppSecret)
        etSymbol = findViewById(R.id.etSymbol)
        etQty = findViewById(R.id.etQty)
        tvLog = findViewById(R.id.tvLog)

        spEnv.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("SIM (demo, safe)", "LIVE (real money)")
        )

        etAppKey.setText(saxo.appKey)
        etAppSecret.setText(saxo.appSecret)

        findViewById<Button>(R.id.btnConnect).setOnClickListener { openLogin() }
        findViewById<Button>(R.id.btnStatus).setOnClickListener { accountStatus() }
        findViewById<Button>(R.id.btnBuy).setOnClickListener { placeOrder("Buy") }
        findViewById<Button>(R.id.btnSell).setOnClickListener { placeOrder("Sell") }

        handleAuthCode(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCode(intent)
    }

    private fun handleAuthCode(intent: Intent?) {
        val uri: Uri? = intent?.data
        val code = uri?.getQueryParameter("code") ?: return
        saveFields()
        runAsync(
            { saxo.exchangeCode(code); "Authenticated OK. Token saved." },
            { log(it) }
        )
    }

    private fun saveFields() {
        saxo.env = if (spEnv.selectedItemPosition == 1) "LIVE" else "SIM"
        saxo.appKey = etAppKey.text.toString().trim()
        saxo.appSecret = etAppSecret.text.toString().trim()
    }

    private fun openLogin() {
        saveFields()
        if (saxo.appKey.isBlank() || saxo.appSecret.isBlank()) {
            Toast.makeText(this, "Enter App Key and App Secret first", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(saxo.authUrl)))
    }

    private fun accountStatus() {
        saveFields()
        val account = accountOrPrompt() ?: return
        runAsync(
            { "Account: ${account.name} (${account.currency})\n" +
                saxo.getBalance(account.accountKey) + "\n" +
                saxo.getPositions(account.accountKey) },
            { log(it) }
        )
    }

    private fun placeOrder(buySell: String) {
        saveFields()
        val account = accountOrPrompt() ?: return
        val symbol = etSymbol.text.toString().trim()
        val qty = etQty.text.toString().toIntOrNull()
        if (symbol.isEmpty() || qty == null || qty <= 0) {
            Toast.makeText(this, "Enter symbol and quantity", Toast.LENGTH_LONG).show()
            return
        }
        val isLive = saxo.env == "LIVE"
        val warn = if (isLive) "\n\nWARNING: this is a REAL order on a REAL account with real money." else ""
        AlertDialog.Builder(this)
            .setTitle("Confirm $buySell")
            .setMessage("$buySell $qty x $symbol on ${saxo.env}$warn")
            .setPositiveButton("Place order") { _, _ ->
                runAsync(
                    {
                        val uic = saxo.searchUic(symbol)
                            ?: throw IllegalStateException("Symbol not found: $symbol")
                        saxo.placeOrder(account.accountKey, uic.second, qty, buySell)
                    },
                    { log(it) }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun accountOrPrompt(): SaxoAccount? {
        return try {
            val accounts = saxo.getAccounts()
            if (accounts.isEmpty()) throw IllegalStateException("No Saxo accounts found")
            accounts[0]
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Error", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun runAsync(work: () -> String, onDone: (String) -> Unit) {
        Toast.makeText(this, "Working...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = try {
                work()
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            runOnUiThread { onDone(result) }
        }.start()
    }

    private fun log(text: String) {
        tvLog.text = text
    }
}
