package com.example.websocket.websocket_Data_Connection.analysis;

import com.example.websocket.websocket_Data_Connection.model.Candle;
import com.example.websocket.websocket_Data_Connection.model.Trade;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Market statistics as pure functions: lists in, numbers out, no Spring, files or clock.
 * That makes every formula testable with small hand-calculated examples
 * (see {@code MarketAnalyticsTest}).
 */
public final class MarketAnalytics {

    private MarketAnalytics() {
    }

    /**
     * Groups trades into candles of a fixed interval. A candle covers
     * {@code [openTime, openTime + interval)}; intervals with no trades produce no candle.
     *
     * @param trades trades of a single symbol, in any order
     */
    public static List<Candle> toCandles(List<Trade> trades, Duration interval) {
        long intervalMillis = interval.toMillis();
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (trades.isEmpty()) {
            return List.of();
        }
        String symbol = trades.getFirst().symbol();
        if (trades.stream().anyMatch(t -> !t.symbol().equals(symbol))) {
            throw new IllegalArgumentException("toCandles expects trades of one symbol");
        }

        List<Trade> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator.comparingLong(Trade::timestamp));

        List<Candle> candles = new ArrayList<>();
        long bucket = Math.floorDiv(sorted.getFirst().timestamp(), intervalMillis) * intervalMillis;
        double open = sorted.getFirst().price();
        double high = open;
        double low = open;
        double close = open;
        double volume = 0;
        int count = 0;

        for (Trade trade : sorted) {
            long tradeBucket = Math.floorDiv(trade.timestamp(), intervalMillis) * intervalMillis;
            if (tradeBucket != bucket) {
                candles.add(new Candle(symbol, bucket, open, high, low, close, volume, count));
                bucket = tradeBucket;
                open = trade.price();
                high = open;
                low = open;
                volume = 0;
                count = 0;
            }
            high = Math.max(high, trade.price());
            low = Math.min(low, trade.price());
            close = trade.price();
            volume += trade.volume();
            count++;
        }
        candles.add(new Candle(symbol, bucket, open, high, low, close, volume, count));
        return candles;
    }

    /**
     * Volume-weighted average price: sum(price * volume) / sum(volume).
     * Empty when there are no trades or the total volume is zero.
     */
    public static OptionalDouble vwap(List<Trade> trades) {
        double priceTimesVolume = 0;
        double totalVolume = 0;
        for (Trade trade : trades) {
            priceTimesVolume += trade.price() * trade.volume();
            totalVolume += trade.volume();
        }
        return totalVolume > 0 ? OptionalDouble.of(priceTimesVolume / totalVolume) : OptionalDouble.empty();
    }

    /**
     * Simple moving average. Element {@code i} of the result is the mean of
     * {@code values[i .. i + window - 1]}, so the result has {@code n - window + 1} elements
     * (none if there are fewer values than the window).
     */
    public static List<Double> simpleMovingAverage(List<Double> values, int window) {
        if (window < 1) {
            throw new IllegalArgumentException("window must be at least 1");
        }
        List<Double> averages = new ArrayList<>();
        double sum = 0;
        for (int i = 0; i < values.size(); i++) {
            sum += values.get(i);
            if (i >= window) {
                sum -= values.get(i - window);
            }
            if (i >= window - 1) {
                averages.add(sum / window);
            }
        }
        return averages;
    }

    /**
     * Volatility: the sample standard deviation of log returns ln(p[i] / p[i-1]), as a
     * fraction (0.001 = 0.1%) per step between prices. With one-minute closes it is the
     * typical one-minute move. It is not annualised.
     * Needs at least three prices (two returns); otherwise empty.
     */
    public static OptionalDouble volatility(List<Double> prices) {
        if (prices.size() < 3) {
            return OptionalDouble.empty();
        }
        double[] returns = new double[prices.size() - 1];
        for (int i = 1; i < prices.size(); i++) {
            returns[i - 1] = Math.log(prices.get(i) / prices.get(i - 1));
        }
        double mean = 0;
        for (double r : returns) {
            mean += r;
        }
        mean /= returns.length;
        double squaredDeviations = 0;
        for (double r : returns) {
            squaredDeviations += (r - mean) * (r - mean);
        }
        return OptionalDouble.of(Math.sqrt(squaredDeviations / (returns.length - 1)));
    }

    public static List<Double> closes(List<Candle> candles) {
        return candles.stream().map(Candle::close).toList();
    }

    /**
     * Checks our candles (built from the live feed) against reference candles for the same
     * intervals (the exchange's own). Only intervals present in both lists are compared.
     * Empty when no interval matches.
     */
    public static Optional<CandleComparison> compare(List<Candle> ours, List<Candle> reference) {
        Map<Long, Candle> referenceByTime = new HashMap<>();
        for (Candle candle : reference) {
            referenceByTime.put(candle.openTime(), candle);
        }

        int compared = 0;
        double sumAbsDiffPct = 0;
        double maxAbsDiffPct = 0;
        double ourVolume = 0;
        double referenceVolume = 0;

        for (Candle candle : ours) {
            Candle ref = referenceByTime.get(candle.openTime());
            if (ref == null || ref.close() == 0) {
                continue;
            }
            double absDiffPct = Math.abs(candle.close() - ref.close()) / ref.close() * 100;
            sumAbsDiffPct += absDiffPct;
            maxAbsDiffPct = Math.max(maxAbsDiffPct, absDiffPct);
            ourVolume += candle.volume();
            referenceVolume += ref.volume();
            compared++;
        }

        if (compared == 0) {
            return Optional.empty();
        }
        double volumeCoveragePct = referenceVolume > 0 ? ourVolume / referenceVolume * 100 : 0;
        return Optional.of(new CandleComparison(compared, sumAbsDiffPct / compared, maxAbsDiffPct, volumeCoveragePct));
    }
}
