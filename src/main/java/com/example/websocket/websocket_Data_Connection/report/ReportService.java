package com.example.websocket.websocket_Data_Connection.report;

import com.example.websocket.websocket_Data_Connection.analysis.CandleComparison;
import com.example.websocket.websocket_Data_Connection.analysis.MarketAnalytics;
import com.example.websocket.websocket_Data_Connection.history.BinanceHistoryClient;
import com.example.websocket.websocket_Data_Connection.model.Candle;
import com.example.websocket.websocket_Data_Connection.model.Trade;
import com.example.websocket.websocket_Data_Connection.storage.RecentTradeBuffer;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static com.example.websocket.websocket_Data_Connection.storage.CsvTradeStore.formatNumber;

/**
 * Builds reports from the live trades in memory. Combines the data ({@link RecentTradeBuffer},
 * {@link BinanceHistoryClient}) with the formulas ({@link MarketAnalytics}); the controller
 * only translates HTTP to these calls.
 */
@Service
public class ReportService {

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final int MOVING_AVERAGE_WINDOW = 5;

    private final RecentTradeBuffer buffer;
    private final BinanceHistoryClient history;
    private final Clock clock;

    public ReportService(RecentTradeBuffer buffer, BinanceHistoryClient history, Clock clock) {
        this.buffer = buffer;
        this.history = history;
        this.clock = clock;
    }

    public MarketReport summary(String symbol, int minutes) {
        long to = clock.millis();
        long from = to - minutes * 60_000L;
        List<Trade> trades = buffer.since(symbol, from);
        if (trades.isEmpty()) {
            return new MarketReport(symbol, from, to, 0, 0, null, null, null, null, null, null, null, null);
        }

        double first = trades.getFirst().price();
        double last = trades.getLast().price();
        double high = trades.stream().mapToDouble(Trade::price).max().orElseThrow();
        double low = trades.stream().mapToDouble(Trade::price).min().orElseThrow();
        double volume = trades.stream().mapToDouble(Trade::volume).sum();

        List<Double> closes = MarketAnalytics.closes(MarketAnalytics.toCandles(trades, ONE_MINUTE));
        List<Double> movingAverage = MarketAnalytics.simpleMovingAverage(closes, MOVING_AVERAGE_WINDOW);
        OptionalDouble volatility = MarketAnalytics.volatility(closes);

        return new MarketReport(symbol, from, to, trades.size(), volume,
                first, last, high, low,
                (last - first) / first * 100,
                toNullable(MarketAnalytics.vwap(trades)),
                volatility.isPresent() ? volatility.getAsDouble() * 100 : null,
                movingAverage.isEmpty() ? null : movingAverage.getLast());
    }

    public List<Candle> candles(String symbol, int minutes, Duration interval) {
        long from = clock.millis() - minutes * 60_000L;
        return MarketAnalytics.toCandles(buffer.since(symbol, from), interval);
    }

    /**
     * Compares our one-minute candles with Binance's for the same minutes. Two minutes are
     * left out: the current one (still open) and our first one, which is usually partial
     * because recording rarely starts exactly on a minute boundary.
     */
    public DataQualityReport dataQuality(String symbol, int minutes) {
        long now = clock.millis();
        long currentMinute = Math.floorDiv(now, ONE_MINUTE.toMillis()) * ONE_MINUTE.toMillis();
        List<Candle> ours = candles(symbol, minutes, ONE_MINUTE).stream()
                .filter(c -> c.openTime() < currentMinute)
                .skip(1)
                .toList();
        if (ours.isEmpty()) {
            return new DataQualityReport(symbol, 0, null, null, null,
                    "Not enough recorded data yet: need at least two complete minutes.");
        }

        // Sized by time span, not by ours.size(): minutes without trades leave gaps in our list.
        long spanMinutes = (ours.getLast().openTime() - ours.getFirst().openTime()) / ONE_MINUTE.toMillis() + 1;
        int limit = (int) Math.min(spanMinutes, BinanceHistoryClient.MAX_LIMIT);
        List<Candle> reference = history.fetchCandles(symbol, "1m", limit, ours.getFirst().openTime());
        Optional<CandleComparison> comparison = MarketAnalytics.compare(ours, reference);
        if (comparison.isEmpty()) {
            return new DataQualityReport(symbol, 0, null, null, null,
                    "No overlapping minutes with the exchange's candles.");
        }
        CandleComparison c = comparison.get();
        return new DataQualityReport(symbol, c.comparedIntervals(),
                c.meanAbsCloseDiffPercent(), c.maxAbsCloseDiffPercent(), c.volumeCoveragePercent(),
                "Close prices should match within a few hundredths of a percent. Volume coverage below "
                        + "100% means the feed delivered only part of the exchange's trades.");
    }

    /** One-minute candles as CSV, for Excel or pandas. Times are ISO-8601 UTC. */
    public String candlesCsv(String symbol, int minutes) {
        StringBuilder csv = new StringBuilder("open_time_utc,open,high,low,close,volume,trades\n");
        for (Candle c : candles(symbol, minutes, ONE_MINUTE)) {
            csv.append(Instant.ofEpochMilli(c.openTime())).append(',')
                    .append(formatNumber(c.open())).append(',')
                    .append(formatNumber(c.high())).append(',')
                    .append(formatNumber(c.low())).append(',')
                    .append(formatNumber(c.close())).append(',')
                    .append(formatNumber(c.volume())).append(',')
                    .append(c.tradeCount()).append('\n');
        }
        return csv.toString();
    }

    private static Double toNullable(OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : null;
    }
}
