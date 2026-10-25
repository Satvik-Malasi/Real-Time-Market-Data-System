package com.example.websocket.websocket_Data_Connection.analysis;

import com.example.websocket.websocket_Data_Connection.model.Candle;
import com.example.websocket.websocket_Data_Connection.model.Trade;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Every expected value here is worked out by hand in the comments, so the formulas are checked, not just run. */
class MarketAnalyticsTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private static Trade trade(double price, double volume, long timestamp) {
        return new Trade("BTC", price, volume, timestamp);
    }

    @Test
    void groupsTradesIntoOneMinuteCandles() {
        List<Trade> trades = List.of(
                trade(102, 1, 70_000),   // minute 1 (60_000 .. 119_999), listed out of order on purpose
                trade(100, 2, 0),        // minute 0
                trade(105, 1, 30_000),   // minute 0
                trade(99, 3, 59_999),    // minute 0, last millisecond
                trade(101, 1, 61_000));  // minute 1

        List<Candle> candles = MarketAnalytics.toCandles(trades, MINUTE);

        assertThat(candles).containsExactly(
                // open 100 (first), high 105, low 99, close 99 (last), volume 2+1+3 = 6, 3 trades
                new Candle("BTC", 0, 100, 105, 99, 99, 6, 3),
                // sorted by time: 101 at 61s, then 102 at 70s
                new Candle("BTC", 60_000, 101, 102, 101, 102, 2, 2));
    }

    @Test
    void minutesWithoutTradesProduceNoCandle() {
        List<Candle> candles = MarketAnalytics.toCandles(List.of(trade(1, 1, 0), trade(2, 1, 180_000)), MINUTE);

        assertThat(candles).extracting(Candle::openTime).containsExactly(0L, 180_000L);
    }

    @Test
    void refusesToMixSymbols() {
        List<Trade> mixed = List.of(new Trade("A", 1, 1, 0), new Trade("B", 1, 1, 0));

        assertThatThrownBy(() -> MarketAnalytics.toCandles(mixed, MINUTE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void vwapWeighsPricesByVolume() {
        // (100*1 + 110*3) / (1+3) = 430 / 4 = 107.5   (the plain average would be 105)
        assertThat(MarketAnalytics.vwap(List.of(trade(100, 1, 0), trade(110, 3, 1))).getAsDouble())
                .isEqualTo(107.5);
    }

    @Test
    void vwapIsEmptyWithoutVolume() {
        assertThat(MarketAnalytics.vwap(List.of())).isEmpty();
        assertThat(MarketAnalytics.vwap(List.of(trade(100, 0, 0)))).isEmpty();
    }

    @Test
    void simpleMovingAverage() {
        // window 3 over 1,2,3,4,5: (1+2+3)/3=2, (2+3+4)/3=3, (3+4+5)/3=4
        assertThat(MarketAnalytics.simpleMovingAverage(List.of(1.0, 2.0, 3.0, 4.0, 5.0), 3))
                .containsExactly(2.0, 3.0, 4.0);
        assertThat(MarketAnalytics.simpleMovingAverage(List.of(1.0, 2.0), 3)).isEmpty();
    }

    @Test
    void volatilityIsTheSampleStdDevOfLogReturns() {
        // prices 100 -> 110 -> 99: returns ln(1.1) = 0.0953102, ln(0.9) = -0.1053605
        // mean = -0.0050252; deviations +/-0.1003353; sample variance = 2 * 0.1003353^2 / (2-1)
        // std dev = sqrt(0.0201345) = 0.1418962
        assertThat(MarketAnalytics.volatility(List.of(100.0, 110.0, 99.0)).getAsDouble())
                .isCloseTo(0.1418962, within(1e-6));
    }

    @Test
    void constantPricesHaveZeroVolatility() {
        assertThat(MarketAnalytics.volatility(List.of(50.0, 50.0, 50.0, 50.0)).getAsDouble()).isZero();
    }

    @Test
    void volatilityNeedsAtLeastThreePrices() {
        assertThat(MarketAnalytics.volatility(List.of(1.0, 2.0))).isEmpty();
    }

    @Test
    void comparesOnlyMatchingIntervals() {
        List<Candle> ours = List.of(
                candle(0, 100.0, 0.5),
                candle(60_000, 101.0, 1.0),
                candle(120_000, 999.0, 9.0)); // no reference candle for this minute: ignored
        List<Candle> reference = List.of(
                candle(0, 100.0, 1.0),
                candle(60_000, 100.0, 1.0));

        CandleComparison result = MarketAnalytics.compare(ours, reference).orElseThrow();

        assertThat(result.comparedIntervals()).isEqualTo(2);
        // close differences: 0% and |101-100|/100 = 1%  -> mean 0.5%, max 1%
        assertThat(result.meanAbsCloseDiffPercent()).isCloseTo(0.5, within(1e-9));
        assertThat(result.maxAbsCloseDiffPercent()).isCloseTo(1.0, within(1e-9));
        // volume: (0.5 + 1.0) / (1.0 + 1.0) = 75%
        assertThat(result.volumeCoveragePercent()).isCloseTo(75.0, within(1e-9));
    }

    @Test
    void comparisonIsEmptyWhenNothingOverlaps() {
        assertThat(MarketAnalytics.compare(List.of(candle(0, 1, 1)), List.of(candle(60_000, 1, 1)))).isEmpty();
    }

    private static Candle candle(long openTime, double close, double volume) {
        return new Candle("BTC", openTime, close, close, close, close, volume, 1);
    }
}
