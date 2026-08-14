package com.autotrader.app

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.autotrader.app.data.StateStore
import com.autotrader.app.engine.TradeEngine
import com.autotrader.app.worker.TradeWorker
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var tvEquity: TextView
    private lateinit var tvDetails: TextView
    private lateinit var tvPositions: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvAuto: TextView
    private lateinit var btnAuto: Button

    private var autoRunning = false

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvEquity = findViewById(R.id.tvEquity)
        tvDetails = findViewById(R.id.tvDetails)
        tvPositions = findViewById(R.id.tvPositions)
        tvLog = findViewById(R.id.tvLog)
        tvAuto = findViewById(R.id.tvAuto)
        btnAuto = findViewById(R.id.btnAuto)

        findViewById<Button>(R.id.btnDeposit).setOnClickListener { showDepositDialog() }
        findViewById<Button>(R.id.btnRun).setOnClickListener { runBotNow() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnSuggest).setOnClickListener { showSuggestions() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetAccount() }
        findViewById<Button>(R.id.btnSaxo).setOnClickListener {
            startActivity(android.content.Intent(this, SaxoActivity::class.java))
        }
        btnAuto.setOnClickListener { toggleAuto() }

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData("trade_bot")
            .observe(this) { infos ->
                autoRunning = infos.any { !it.state.isFinished }
                tvAuto.text = if (autoRunning) "Auto-trading: ON (hourly)" else "Auto-trading: OFF"
                btnAuto.text = if (autoRunning) "Turn auto OFF" else "Turn auto ON"
            }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        refresh()
    }

    private fun showDepositDialog() {
        val input = EditText(this)
        input.hint = "Virtual amount (e.g. 1000)"
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        AlertDialog.Builder(this)
            .setTitle("Deposit virtual money")
            .setView(input)
            .setPositiveButton("Deposit") { _, _ ->
                val amt = input.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                if (amt > 0) {
                    StateStore.deposit(this, amt)
                    Toast.makeText(this, "Deposited ${TradeEngine.money(amt)}. Play money only.", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runBotNow() {
        if (!TradeEngine.marketIsOpen()) {
            Toast.makeText(this, "Market closed (Mon-Fri 9:30-16:00 NY). Fetching anyway.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnRun).isEnabled = false
        Thread {
            val result = try {
                TradeEngine.runBot(this)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                findViewById<Button>(R.id.btnRun).isEnabled = true
                if (result == null) {
                    Toast.makeText(this, "Bot run failed - check network.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, result.summary, Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }.start()
    }

    private fun toggleAuto() {
        val wm = WorkManager.getInstance(this)
        if (autoRunning) {
            wm.cancelUniqueWork("trade_bot")
            Toast.makeText(this, "Auto-trading OFF", Toast.LENGTH_SHORT).show()
        } else {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<TradeWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork("trade_bot", ExistingPeriodicWorkPolicy.UPDATE, request)
            Toast.makeText(this, "Auto-trading ON (hourly, during market hours)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSuggestions() {
        findViewById<Button>(R.id.btnSuggest).isEnabled = false
        Thread {
            val lines = try {
                TradeEngine.suggestions()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                findViewById<Button>(R.id.btnSuggest).isEnabled = true
                val text = if (lines.isEmpty()) "Failed - check network." else lines.joinToString("\n")
                AlertDialog.Builder(this)
                    .setTitle("Suggested operations (after costs)")
                    .setMessage(text)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    private fun resetAccount() {
        AlertDialog.Builder(this)
            .setTitle("Reset account")
            .setMessage("Clear all virtual cash, positions and history?")
            .setPositiveButton("Yes") { _, _ ->
                StateStore.reset(this)
                refresh()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun refresh() {
        Thread {
            val cash = StateStore.cash(this)
            val deposits = StateStore.depositsTotal(this)
            val positions = StateStore.positions(this)

            val equityText = TradeEngine.money(TradeEngine.equity(this))
            val detailsText = "Cash: %s    Deposits: %s".format(
                TradeEngine.money(cash), TradeEngine.money(deposits)
            )

            val positionsText = if (positions.isEmpty()) {
                "No open positions."
            } else {
                val sb = StringBuilder("POSITIONS\n")
                for ((sym, p) in positions.toSortedMap()) {
                    val price = TradeEngine.priceOrAvg(this, sym)
                    val pnl = (price / p.avgPrice - 1) * 100
                    sb.append("%s %s sh @ %s (P&L %s)\n".format(
                        sym,
                        String.format(java.util.Locale.US, "%.4f", p.shares),
                        TradeEngine.money(p.avgPrice),
                        String.format(java.util.Locale.US, "%+.1f%%", pnl)
                    ))
                }
                sb.toString().trimEnd()
            }

            val recent = StateStore.history(this).takeLast(10)
            val logText = if (recent.isEmpty()) "No trades yet." else
                recent.joinToString("\n") {
                    "%s %s %ss sh @ %s  %s".format(
                        it.action, it.symbol, it.shares, TradeEngine.money(it.price), it.date
                    )
                }

            runOnUiThread {
                tvEquity.text = equityText
                tvDetails.text = detailsText
                tvPositions.text = positionsText
                tvLog.text = logText
            }
        }.start()
    }
}
