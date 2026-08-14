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
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetAccount() }
        btnAuto.setOnClickListener { toggleAuto() }

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
                val amt = input.text.toString().toDoubleOrNull() ?: 0.0
                if (amt > 0) {
                    StateStore.deposit(this, amt)
                    Toast.makeText(this, "Deposited $${amt}. Play money only.", Toast.LENGTH_SHORT).show()
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
        val isRunning = wm.getWorkInfosForUniqueWork("trade_bot").get().any { !it.state.isFinished }
        if (isRunning) {
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
        refresh()
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
        val cash = StateStore.cash(this)
        val deposits = StateStore.depositsTotal(this)
        val positions = StateStore.positions(this)

        tvEquity.text = "$%.2f".format(TradeEngine.equity(this))
        tvDetails.text = "Cash: $%.2f    Deposits: $%.2f".format(cash, deposits)

        if (positions.isEmpty()) {
            tvPositions.text = "No open positions."
        } else {
            val sb = StringBuilder("POSITIONS\n")
            for ((sym, p) in positions.toSortedMap()) {
                val pnl = (TradeEngine.priceOrAvg(this, sym) / p.avgPrice - 1) * 100
                sb.append("%s %.4f sh @ %.2f (P&L %+.1f%%)\n".format(sym, p.shares, p.avgPrice, pnl))
            }
            tvPositions.text = sb.toString().trimEnd()
        }

        val recent = StateStore.history(this).takeLast(10)
        tvLog.text = if (recent.isEmpty()) "No trades yet." else
            recent.joinToString("\n") {
                "${it.action} ${it.symbol} ${it.shares}sh @ $%.2f  ${it.date}".format(it.price)
            }

        val wm = WorkManager.getInstance(this)
        wm.getWorkInfosForUniqueWorkLiveData("trade_bot").observe(this) { infos ->
            val running = infos.any { !it.state.isFinished }
            tvAuto.text = if (running) "Auto-trading: ON (hourly)" else "Auto-trading: OFF"
            btnAuto.text = if (running) "Turn auto OFF" else "Turn auto ON"
        }
    }
}
