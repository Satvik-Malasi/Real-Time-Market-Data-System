# Live Market Feed (Spring Boot + WebSocket)

Streams live trades from Finnhub over a WebSocket, saves them, analyses them, checks
them against the exchange's own data, and pushes them to a browser dashboard over a
second WebSocket.

```
                    WebSocket (client)                        WebSocket + STOMP (server)
 Finnhub ─────────────────────────────▶ FinnhubStreamClient ─────────────────────────────▶ Browser dashboard
 wss://ws.finnhub.io                      │  (parses, fans out)                              (index.html / app.js)
                                          │                                                        ▲
                         ┌────────────────┼─────────────────┐                                       │ fetch()
                         ▼                ▼                 ▼                                       │
                   CsvTradeStore   RecentTradeBuffer   TradeBroadcaster                     MarketController
                  data/trades.csv   (last 2 h, RAM)    /topic/trades/<sym>                    /api/...
                                          │                                                        │
                                          └──────────▶ ReportService ◀────── BinanceHistoryClient ◀┘
                                                       (MarketAnalytics)      api.binance.com (REST)
```

## Quick start

1. **API key.** Get a free key at finnhub.io. Then either set an environment variable
   (Windows cmd: `set FINNHUB_API_KEY=your_key_here`, PowerShell:
   `$env:FINNHUB_API_KEY="your_key_here"`, macOS/Linux: `export FINNHUB_API_KEY=your_key_here`)

   or create `application-local.properties` in the project root (it is git-ignored):

   ```properties
   finnhub.api-key=your_key_here
   ```

  
2. **Run.**

   ```bash
   ./mvnw spring-boot:run
   ```

  
3. **Open** http://localhost:8080. Trades appear within seconds (BTC trades 24/7).

4. **Test** (no key or internet needed):

   ```bash
   ./mvnw test
   ```

## What is where

| Package | Class | Responsibility |
|---|---|---|
| `config` | `FinnhubProperties`, `StorageProperties`, `HistoryProperties` | Typed settings bound from `application.properties` |
| | `WebSocketConfig` | The app's own WebSocket endpoint (`/ws`) and STOMP broker (`/topic`) |
| `model` | `Trade`, `Candle` | Immutable data records |
| `stream` | `FinnhubStreamClient` | Connection to Finnhub: subscribe, reconnect with backoff, fan out trades |
| | `FinnhubMessageParser` | JSON message → `List<Trade>`, skipping bad entries |
| | `TradeListener` | Interface every trade consumer implements |
| `storage` | `CsvTradeStore` | Appends trades to `data/trades.csv`, reads them back |
| | `RecentTradeBuffer` | Last 2 hours of trades in memory, bounded by time and count |
| `analysis` | `MarketAnalytics` | Pure functions: candles, VWAP, moving average, volatility, comparison |
| `history` | `BinanceHistoryClient` | Historical candles from Binance's public REST API |
| `report` | `ReportService` | Combines the data and the formulas into reports |
| `realtime` | `TradeBroadcaster` | Pushes trades to browsers over STOMP |
| `web` | `MarketController`, `ApiExceptionHandler` | HTTP API and consistent JSON errors |

If you are comparing with the first version (kept in `_old_version/` for reference):
`stockWebsocketClient` → `stream/FinnhubStreamClient` + `FinnhubMessageParser`,
`stockUpdate` → `model/Trade`, `CsvWriter` → `storage/CsvTradeStore`,
`HistoricalAnalysis` → `analysis/MarketAnalytics`, `restApiClient` → `history/BinanceHistoryClient`,
`RealTimeGraph` → `realtime/TradeBroadcaster` + `static/app.js`,
`ReportGeneration` → `report/ReportService`.

## HTTP API

All endpoints take an optional `symbol` (default: the first in `finnhub.symbols`).
Errors come back as JSON problem details (`{"status":400,"detail":"..."}`).

| Endpoint | Returns |
|---|---|
| `GET /api/status` | Is the feed connected, trades received, last trade time, reconnect attempts |
| `GET /api/report?minutes=15` | Count, volume, first/last/high/low, change %, VWAP, 1-min volatility, 5-min moving average |
| `GET /api/candles?minutes=60&intervalMinutes=1` | Candles built from the live trades |
| `GET /api/candles.csv?minutes=60` | The same as a CSV download |
| `GET /api/history?interval=1h&limit=24` | Historical candles from Binance (`interval`: 1m 3m 5m 15m 30m 1h 4h 1d, `limit` ≤ 1000) |
| `GET /api/data-quality?minutes=15` | Our one-minute candles compared with Binance's (see below) |

WebSocket: connect a STOMP client to `ws://localhost:8080/ws` and subscribe to
`/topic/trades/BINANCE:BTCUSDT`. Each message is a JSON array of trades.

## Design decisions and trade-offs

**Two WebSocket libraries, on purpose.** The app is a WebSocket *client* to Finnhub
(Java-WebSocket, a small library with built-in ping/pong) and a WebSocket *server* to
browsers (Spring's STOMP support). STOMP adds named topics on top of raw WebSocket, so the
server publishes with one call and the browser subscribes by name. For a one-way feed,
Server-Sent Events would be simpler; WebSocket is used because it is the subject here.

**Observer pattern for consumers.** `FinnhubStreamClient` knows nothing about CSV files or
browsers. It hands each batch to every `TradeListener` bean. A new consumer (a database
writer, an alert) is one new class. A failing listener is logged and does not stop the others.

**Lifecycle.** The client implements `SmartLifecycle`, so Spring connects after every bean
exists and disconnects cleanly on shutdown. The first version connected in `@PostConstruct`,
before the app was fully started, and never disconnected.

**Reconnect with exponential backoff.** Any close (network drop, server restart, failed
attempt) schedules a new connection after 1, 2, 4 … seconds, capped at 60 s, and the delay
resets after a successful connection. Backoff avoids hammering a server that is down. If no
message (not even a ping) arrives for 30 s, the library treats the connection as dead.

**CSV storage, flushed per batch.** CSV keeps the data readable in Excel or pandas with no
extra dependency. The file stays open and is flushed once per WebSocket message: cheaper than
opening it per trade, and at most one batch is lost in a crash. For months of data, multiple
readers, or queries by time, use a database (SQLite, or PostgreSQL with TimescaleDB).

**Bounded memory.** `RecentTradeBuffer` drops trades older than `storage.memory-retention`
and caps the count at `storage.memory-max-trades`, so a long run cannot exhaust memory.

**`double`, not `BigDecimal`.** Fine for statistics and charts. For accounting (balances,
order amounts) use `BigDecimal`, since `double` cannot represent most decimal fractions exactly.
`volume` is a `double` because crypto trades are fractional; the first version used `int`,
which silently stored every volume as 0.

**Historical data from Binance, not Finnhub.** Finnhub's candle endpoints need a paid plan
(the free tier answers 403). Binance's are public. This limits history to `BINANCE:*` symbols.

**Pure analysis functions.** `MarketAnalytics` takes lists and returns numbers: no Spring,
files, network or clock. Every formula is tested against values worked out by hand.

**Injected `Clock`.** Classes that need the current time take a `Clock`, so tests can fix
the time instead of depending on when they run.

**Secrets.** The key is read from an environment variable or a git-ignored file, the app
refuses to start without one (instead of retrying forever), and the URL containing the key
is never logged.

## Checking the data: `/api/data-quality`

A feed can look healthy and still be wrong; the first version stored every volume as 0
without anyone noticing. This endpoint builds one-minute candles from the recorded trades
and compares them with Binance's official one-minute candles for the same minutes:

- **Close-price difference:** should be a few hundredths of a percent at most. Larger values
  mean trades are missing or timestamps are off.
- **Volume coverage:** our volume ÷ the exchange's volume. Below 100% means the feed
  delivers only part of the exchange's trades. Measure it before drawing conclusions from
  volume-based statistics.

The current (unfinished) minute and our first minute (usually partial, since recording rarely
starts on a minute boundary) are left out.

## Tests

`./mvnw test` runs 47 tests in about 15 seconds without a key or internet:

| Test | What it proves |
|---|---|
| `FinnhubMessageParserTest` | Trades, pings, errors, invalid JSON and malformed entries are handled; fractional volume survives |
| `FinnhubStreamClientTest` | Backoff timing; a failing listener does not affect others; missing key fails at startup |
| `CsvTradeStoreTest` | Header written once; append; round-trip; no scientific notation; bad lines skipped |
| `RecentTradeBufferTest` | Filtering, time-based and size-based eviction (with a controllable clock) |
| `MarketAnalyticsTest` | Candles, VWAP, moving average, volatility, comparison, each against hand-worked values |
| `BinanceHistoryClientTest` | Response mapping and error handling against a fake HTTP server |
| `ReportServiceTest` | Report numbers; data-quality minute selection |
| `MarketControllerTest` | Parameter validation and status codes (400, 502) |
| `TradeBroadcastIntegrationTest` | A real STOMP client over a real WebSocket receives broadcast trades |
| `WebsocketDataConnectionApplicationTests` | The whole app starts and serves the dashboard |

## Limitations

- One server only: the in-memory STOMP broker and trade buffer are not shared between instances.
- The live chart starts empty on page load; it does not fetch trades from before the page opened.
- After a restart, reports only cover trades received since the restart (older ones stay in the CSV).
- `CsvTradeStore.readAll` reads the whole file into memory.
- Historical data and the quality check work only for `BINANCE:*` symbols.

## Ideas for future version

- Store trades in SQLite or PostgreSQL and query by time range.
- Alerts: notify when the price moves more than N% within a minute, then measure how often
  the alert fires and how quickly after the move.
- Several symbols on one dashboard, or correlation between them.
- Measure end-to-end delay: Finnhub's trade timestamp versus the time the browser receives it.
- Replace polling of `/api/report` with server-pushed updates.
