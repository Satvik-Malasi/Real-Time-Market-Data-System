# Adding MySQL: step by step

Goal: live trades are saved in MySQL, and reports read from MySQL, so data survives a
restart and you can query any time range (not just the last 2 hours in memory).

When you finish, each résumé claim is backed by something you built:

| Résumé claim | Step that makes it true |
|---|---|
| "stored them in MySQL" | Steps 1–4 |
| "batched inserts" | Step 4 (`batchUpdate` + `rewriteBatchedStatements`) |
| "index on (symbol, timestamp) for time-range queries" | Step 3 (index) + Step 5 (queries use it) + Step 7 (`EXPLAIN` proves it) |
| "tests covering storage" | Step 6 |

Do the steps in order and check each "Done when" before moving on.

---

## Step 1 — Install MySQL and create a database

Install MySQL 8 (MySQL Installer on Windows), or with Docker:

```bash
docker run --name market-mysql -e MYSQL_ROOT_PASSWORD=rootpass -p 3306:3306 -d mysql:8.4
```

Then, in the MySQL shell (`mysql -u root -p`) create a database and a user just for this app:

```sql
CREATE DATABASE market_data;
CREATE USER 'market_app'@'localhost' IDENTIFIED BY 'choose-a-password';
GRANT SELECT, INSERT, CREATE, INDEX ON market_data.* TO 'market_app'@'localhost';
```

Why a separate user: the app should not run as `root`. It only gets the permissions it needs.
(With Docker, use `'market_app'@'%'` instead of `@'localhost'`.)

**Done when:** `mysql -u market_app -p market_data` logs you in.

---

## Step 2 — Dependencies and connection settings

In `pom.xml` add:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

The password is a secret, so it goes in `application-local.properties` (git-ignored),
**not** in `application.properties`:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/market_data?rewriteBatchedStatements=true
spring.datasource.username=market_app
spring.datasource.password=choose-a-password
```

`rewriteBatchedStatements=true` matters: without it, MySQL Connector/J still sends a batch as
separate `INSERT`s, one network round trip each. With it, the driver sends one multi-row `INSERT`.

**Why JDBC (`JdbcTemplate`) and not JPA/Hibernate?** Both work. JdbcTemplate is recommended here because:
- you write the SQL yourself, so you can explain every query in an interview;
- batching is one method call (`batchUpdate`);
- with JPA, an `AUTO_INCREMENT` id (`GenerationType.IDENTITY`) silently **disables** Hibernate's
  insert batching, a common trap that would make the "batched inserts" claim false.

**Done when:** the app starts and the log shows `HikariPool-1 - Start completed`.

---

## Step 3 — The table

Create `src/main/resources/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS trades (
    id          BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    symbol      VARCHAR(32)    NOT NULL,
    price       DECIMAL(20, 8) NOT NULL,
    volume      DECIMAL(28, 10) NOT NULL,
    trade_time  BIGINT         NOT NULL,   -- epoch milliseconds, UTC (same as Trade.timestamp)
    INDEX idx_trades_symbol_time (symbol, trade_time)
);
```

and tell Spring Boot to run it at startup (in `application.properties`, this one is not secret):

```properties
spring.sql.init.mode=always
```

Decisions to understand (interview material):
- **`DECIMAL`, not `DOUBLE` or `INT`.** `DECIMAL` stores 0.00042 exactly. `INT` was the original
  bug (every volume became 0); `DOUBLE` stores approximations.
- **Index on `(symbol, trade_time)`, in that order.** Every report asks for "one symbol, between
  two times". MySQL uses the first column to find the symbol, then the second to read just that
  time range. The other order `(trade_time, symbol)` would be much worse for this query.
- **`trade_time` as `BIGINT` epoch millis.** Matches the Java model, and avoids time-zone
  confusion. The alternative, `DATETIME(3)`, is easier to read in SQL (`FROM_UNIXTIME(trade_time/1000)`
  gives you that when you need it).
- **No unique constraint on trades.** Finnhub trades have no trade ID, so two genuine trades can
  have identical values. Duplicates are therefore not prevented; say so if asked.

**Done when:** `SHOW CREATE TABLE trades;` shows the table with the index.

---

## Step 4 — Write trades: `storage/MySqlTradeStore`

A new class that implements `TradeListener`, exactly like `CsvTradeStore`. Because of the
observer design, `FinnhubStreamClient` does not change at all: Spring finds the new bean and
gives it every batch.

The core of it:

```java
@Component
public class MySqlTradeStore implements TradeListener {

    private static final String INSERT =
            "INSERT INTO trades (symbol, price, volume, trade_time) VALUES (?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public MySqlTradeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void onTrades(List<Trade> trades) {
        // One batch per WebSocket message: one round trip instead of one per trade.
        jdbc.batchUpdate(INSERT, trades, trades.size(), (ps, trade) -> {
            ps.setString(1, trade.symbol());
            ps.setBigDecimal(2, BigDecimal.valueOf(trade.price()));
            ps.setBigDecimal(3, BigDecimal.valueOf(trade.volume()));
            ps.setLong(4, trade.timestamp());
        });
    }
}
```

Things to decide and be able to explain:
- **Keep the CSV too?** Recommended at first: if MySQL is down, `FinnhubStreamClient` logs the
  error for this listener and the CSV still gets the trades (that is what the "a failing
  listener does not stop the others" test checks). Remove `CsvTradeStore` later if you want.
- **Placeholders (`?`), never string concatenation.** That is what prevents SQL injection.
- **It runs on the WebSocket thread.** A slow database slows the feed. For this volume
  (a few trades per second) it is fine. For much more, you would put trades on a queue and
  write them from a separate thread. Mention this as a known trade-off.

**Done when:** after running the app for a minute, `SELECT COUNT(*) FROM trades;` keeps growing,
and `SELECT * FROM trades ORDER BY id DESC LIMIT 5;` shows non-zero volumes.

---

## Step 5 — Read from MySQL: `storage/TradeRepository`

Without this, MySQL is only a write-only log and "time-range queries" is not true.

```java
@Repository
public class TradeRepository {

    private final JdbcTemplate jdbc;

    public TradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Trade> findBetween(String symbol, long fromMillis, long toMillis) {
        return jdbc.query("""
                SELECT symbol, price, volume, trade_time FROM trades
                WHERE symbol = ? AND trade_time >= ? AND trade_time < ?
                ORDER BY trade_time
                """,
                (rs, row) -> new Trade(rs.getString("symbol"), rs.getDouble("price"),
                        rs.getDouble("volume"), rs.getLong("trade_time")),
                symbol, fromMillis, toMillis);
    }
}
```

Then use it:
1. In `ReportService`, read trades from `TradeRepository` instead of `RecentTradeBuffer`
   (or add a `source=db` option if you want to keep both and compare them).
2. In `MarketController`, the `minutes` limit can grow beyond 120, e.g. up to 7 days,
   because the data is no longer limited by memory.

This fixes two limitations listed in the README: reports now survive a restart, and they are
no longer limited to 2 hours.

**Optional, good SQL practice:** build one-minute candles in SQL and check they equal the Java ones:

```sql
SELECT FLOOR(trade_time / 60000) * 60000 AS open_time,
       MAX(price) AS high, MIN(price) AS low, SUM(volume) AS volume, COUNT(*) AS trades
FROM trades
WHERE symbol = 'BINANCE:BTCUSDT' AND trade_time >= ?
GROUP BY open_time
ORDER BY open_time;
```

(Open and close prices are harder in SQL; that is a good question to work through yourself.)

**Done when:** after restarting the app, `/api/report?minutes=60` still shows the trades
recorded before the restart.

---

## Step 6 — Tests

The Spring Boot tests now need a database. Two options:

| | Testcontainers (real MySQL in Docker) | H2 in MySQL mode |
|---|---|---|
| Setup | needs Docker running | nothing extra |
| Accuracy | tests the real database | H2 only imitates MySQL; some SQL differs |
| Speed | a few seconds to start | instant |

Use Testcontainers if you have Docker; it is the more honest test. Otherwise H2:

```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

and in the test properties: `spring.datasource.url=jdbc:h2:mem:test;MODE=MySQL`.

Write at least:
- `MySqlTradeStoreTest` — write a batch, read it back with `TradeRepository`, and check every
  field, especially that a volume like 0.00042 survives exactly.
- `TradeRepositoryTest` — the time filter is correct at the edges (a trade exactly at `from`
  is included, one exactly at `to` is not) and other symbols are excluded.
- Update `ReportServiceTest` for the new data source.
- Run the whole suite: `./mvnw test` must still pass.

---

## Step 7 — Prove it works, and collect real numbers

1. **The index is used.** In MySQL:

   ```sql
   EXPLAIN SELECT * FROM trades
   WHERE symbol = 'BINANCE:BTCUSDT' AND trade_time >= 0 AND trade_time < 9999999999999;
   ```

   The `key` column must show `idx_trades_symbol_time`. Take a screenshot; it is good
   evidence in an interview. Try the same query with the index dropped and compare.
2. **Data volume.** Let the app run for a few hours, then `SELECT COUNT(*) FROM trades;`.
3. **Accuracy.** Run `/api/data-quality` over that period.

Only numbers you measured here go on your résumé.

---

## Questions to be ready for

- Why `DECIMAL` and not `DOUBLE` for prices?
- Why is the index `(symbol, trade_time)` and not the other way round?
- What does `rewriteBatchedStatements` do, and how would you show it makes a difference?
- What happens to incoming trades if MySQL goes down? What would you change for 1,000 trades/second?
- Why JdbcTemplate and not JPA? When would JPA be the better choice?
- How do you stop someone from injecting SQL through the `symbol` parameter?
- The table will grow forever. What would you do about that? (Ideas: delete or archive old rows,
  partition the table by month.)
