package com.example.websocket.websocket_Data_Connection.storage;

import com.example.websocket.websocket_Data_Connection.model.Trade;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecentTradeBufferTest {

    /** A clock the test can move forward. */
    private static final class TestClock extends Clock {
        private long millis;

        TestClock(long millis) {
            this.millis = millis;
        }

        void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void returnsTradesOfOneSymbolSinceAGivenTime() {
        TestClock clock = new TestClock(10_000);
        RecentTradeBuffer buffer = new RecentTradeBuffer(Duration.ofHours(1), 100, clock);
        buffer.onTrades(List.of(
                new Trade("A", 1, 1, 1_000),
                new Trade("B", 2, 1, 2_000),
                new Trade("A", 3, 1, 3_000)));

        assertThat(buffer.since("A", 2_000)).containsExactly(new Trade("A", 3, 1, 3_000));
        assertThat(buffer.since("A", 0)).hasSize(2);
    }

    @Test
    void dropsTradesOlderThanTheRetention() {
        TestClock clock = new TestClock(60_000);
        RecentTradeBuffer buffer = new RecentTradeBuffer(Duration.ofMinutes(1), 100, clock);
        buffer.onTrades(List.of(new Trade("A", 1, 1, 30_000), new Trade("A", 2, 1, 50_000)));

        clock.advance(Duration.ofSeconds(35)); // now 95s: the trade at 30s is older than one minute

        assertThat(buffer.since("A", 0)).containsExactly(new Trade("A", 2, 1, 50_000));
    }

    @Test
    void neverHoldsMoreThanTheMaximum() {
        RecentTradeBuffer buffer = new RecentTradeBuffer(Duration.ofHours(1), 3, new TestClock(10_000));

        for (int i = 1; i <= 5; i++) {
            buffer.onTrades(List.of(new Trade("A", i, 1, i)));
        }

        assertThat(buffer.size()).isEqualTo(3);
        assertThat(buffer.since("A", 0)).extracting(Trade::price).containsExactly(3.0, 4.0, 5.0);
    }
}
