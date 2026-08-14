package com.autotrader.app.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autotrader.app.R
import com.autotrader.app.engine.TradeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TradeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        return withContext(Dispatchers.IO) {
            try {
                if (!TradeEngine.marketIsOpen()) {
                    return@withContext Result.success()
                }
                val result = TradeEngine.runBot(ctx)
                if (result.trades.isNotEmpty()) notify(result.summary)
                Result.success()
            } catch (e: Exception) {
                Result.retry()
            }
        }
    }

    private fun notify(message: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("trade_bot", "Trading bot", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val hasPerm = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return
        val notif = NotificationCompat.Builder(applicationContext, "trade_bot")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Auto-Trader")
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        manager.notify(42, notif)
    }
}
