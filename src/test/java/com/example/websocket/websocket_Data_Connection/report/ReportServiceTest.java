package com.example.websocket.websocket_Data_Connection.report;

import com.example.websocket.websocket_Data_Connection.history.BinanceHistoryClient;
import com.example.websocket.websocket_Data_Connection.model.Candle;
import com.example.websocket.websocket_Data_Connection.model.Trade;
import com.example.websocket.websocket_Data_Connection.storage.RecentTradeBuffer;
import com.example.websocket.websocket_Data_Connection.config.StorageProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private static final String SYMBOL = "BINANCE:BTCUSDT";
    private static final long MINUTE = 60_000;
    // "Now" is 10:00:30, half-way through minute 600 (counting minutes from the epoch).
    private static final long NOW = 600 * MINUTE + 30_000;

    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    private final RecentTradeBuffer buffer = new RecentTradeBuffer(
            new StorageProperties(Path.of("unused.csv"), Duration.ofHours(2), 10_000), clock);
    private final BinanceHistoryClient history = mock(BinanceHistoryClient.class);
    private final ReportService service = new ReportService(buffer, history, clock);

    @Test
    void emptyWindowGivesZeroCountsAndNoPrices() {
        MarketReport report = service.summary(SYMBOL, 15);

        assertThat(report.tradeCount()).isZero();
        assertThat(report.lastPrice()).isNull();
        assertThat(report.vwap()).isNull();
    }

    @Test
    void summarisesTheTradesInTheWindow() {
        buffer.onTrades(List.of(
                new Trade(SYMBOL, 999, 1, NOW - 20 * MINUTE),   // outside a 15-minute window
                new Trade(SYMBOL, 100, 1, NOW - 10 * MINUTE),
                new Trade(SYMBOL, 110, 3, NOW - 5 * MINUTE),
                new Trade(SYMBOL, 105, 1, NOW - MINUTE)));

        MarketReport report = service.summary(SYMBOL, 15);

        assertThat(report.tradeCount()).isEqualTo(3);
        assertThat(report.totalVolume()).isEqualTo(5);
        assertThat(report.firstPrice()).isEqualTo(100);
        assertThat(report.lastPrice()).isEqualTo(105);
        assertThat(report.highPrice()).isEqualTo(110);
        assertThat(report.lowPrice()).isEqualTo(100);
        assertThat(report.changePercent()).isCloseTo(5.0, within(1e-9));        // (105-100)/100
        assertThat(report.vwap()).isCloseTo(107.0, within(1e-9));              // (100+330+105)/5
        assertThat(report.oneMinuteVolatilityPercent()).isNotNull();          // 3 one-minute closes
        assertThat(report.movingAverage5m()).isNull();                        // fewer than 5 closes
    }

    @Test
    void dataQualitySkipsTheFirstAndTheCurrentMinute() {
        buffer.onTrades(List.of(
                new Trade(SYMBOL, 100, 1, 596 * MINUTE + 50_000), // first recorded minute: probably partial
                new Trade(SYMBOL, 101, 2, 597 * MINUTE + 10_000),
                new Trade(SYMBOL, 102, 2, 599 * MINUTE + 10_000), // minute 598 had no trades
                new Trade(SYMBOL, 103, 1, 600 * MINUTE + 10_000))); // current minute: still open
        when(history.fetchCandles(anyString(), anyString(), anyInt(), anyLong())).thenReturn(List.of(
                new Candle(SYMBOL, 597 * MINUTE, 101, 101, 101, 101, 4, 10),
                new Candle(SYMBOL, 598 * MINUTE, 101, 101, 101, 101, 4, 10),
                new Candle(SYMBOL, 599 * MINUTE, 102, 102, 102, 102, 4, 10)));

        DataQualityReport report = service.dataQuality(SYMBOL, 15);

        // asks for minutes 597..599 = 3 candles, starting at minute 597
        verify(history).fetchCandles(eq(SYMBOL), eq("1m"), eq(3), eq(597 * MINUTE));
        assertThat(report.comparedMinutes()).isEqualTo(2);           // 597 and 599
        assertThat(report.meanAbsCloseDiffPercent()).isZero();
        assertThat(report.volumeCoveragePercent()).isCloseTo(50.0, within(1e-9)); // (2+2)/(4+4)
    }

    @Test
    void dataQualityNeedsCompleteMinutes() {
        buffer.onTrades(List.of(new Trade(SYMBOL, 100, 1, NOW - 1_000)));

        DataQualityReport report = service.dataQuality(SYMBOL, 15);

        assertThat(report.comparedMinutes()).isZero();
        assertThat(report.note()).contains("Not enough");
    }

    @Test
    void candlesCsvHasAHeaderAndOneRowPerMinute() {
        buffer.onTrades(List.of(
                new Trade(SYMBOL, 100, 0.5, 599 * MINUTE),
                new Trade(SYMBOL, 101, 0.00001, 600 * MINUTE)));

        String[] lines = service.candlesCsv(SYMBOL, 60).split("\n");

        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("open_time_utc,open,high,low,close,volume,trades");
        assertThat(lines[2]).isEqualTo("1970-01-01T10:00:00Z,101,101,101,101,0.00001,1");
    }
}
