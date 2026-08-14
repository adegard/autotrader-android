# AutoTrader — Kotlin Android trading bot (virtual money)

An automatic trading bot for Android built in Kotlin. It:

- **Auto-selects stocks** from a 20-stock watchlist using momentum + SMA20/SMA50 trend filter + RSI
- **Makes buy/sell decisions** every hour during US market hours (Mon–Fri, 09:30–16:00 New York)
- **Uses 100% virtual money** — deposit any amount, no bank or card required
- **Runs in the background** via WorkManager (hourly periodic work)

## How it works

- Data: Yahoo Finance chart API (free, no key) — `app/src/main/java/com/autotrader/app/engine/DataFetcher.kt`
- Indicators: SMA, RSI, momentum — `Indicators.kt`
- Strategy: price above SMA20 AND SMA50, RSI < 78, 20-day momentum > 0 to buy.
  Sell when RSI > 78, price breaks SMA20, or -8% stop-loss. — `Strategy.kt`
- State: SharedPreferences (cash, positions, history) — `data/StateStore.kt`
- Background: `worker/TradeWorker.kt` scheduled by WorkManager every hour.

## Build

GitHub Actions builds the APK automatically on push to `main`.
Download it from the **Actions** tab → latest run → "autotrader-debug-apk" artifact.

Or build locally:
```
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Install

1. Allow "install from unknown sources" for the browser/download folder.
2. Open the APK and install.
3. Grant notification permission (for trade alerts).
4. Deposit virtual money and press "Run bot now", or turn auto-trading ON.

## Strategy validation

The strategy was backtested against 2 years of daily data for the watchlist
(`~/trading/backtest.py` on the desktop side). Adding the SMA50 trend filter
outperformed the baseline (+449.9% vs +37.7% buy-and-hold over the test window,
with a lower max drawdown). Backtests are optimistic: no fees/slippage and
survivorship bias in the watchlist — treat results as relative guidance only.

## Disclaimer

This is a simulation for learning. No real money, no real trades. Not investment advice.
