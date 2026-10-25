package com.example.websocket.websocket_Data_Connection.stream;

import com.example.websocket.websocket_Data_Connection.config.FinnhubProperties;
import com.example.websocket.websocket_Data_Connection.model.Trade;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests the client's logic without opening a network connection. */
class FinnhubStreamClientTest {

    private static final String TRADE_MESSAGE = """
            {"type":"trade","data":[{"p":100.0,"s":"BINANCE:BTCUSDT","t":1000,"v":0.5}]}""";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC);

    @Test
    void backoffDoublesUpToTheMaximum() {
        Duration max = Duration.ofSeconds(60);

        assertThat(FinnhubStreamClient.backoffDelay(1, max)).hasSeconds(1);
        assertThat(FinnhubStreamClient.backoffDelay(2, max)).hasSeconds(2);
        assertThat(FinnhubStreamClient.backoffDelay(3, max)).hasSeconds(4);
        assertThat(FinnhubStreamClient.backoffDelay(6, max)).hasSeconds(32);
        assertThat(FinnhubStreamClient.backoffDelay(7, max)).hasSeconds(60);
        assertThat(FinnhubStreamClient.backoffDelay(500, max)).hasSeconds(60);
    }

    @Test
    void everyListenerGetsTheTradesEvenIfOneFails() {
        List<Trade> received = new ArrayList<>();
        TradeListener broken = trades -> {
            throw new IllegalStateException("disk full");
        };
        FinnhubStreamClient client = client(properties("key", true), List.of(broken, received::addAll));

        client.handleMessage(TRADE_MESSAGE);

        assertThat(received).containsExactly(new Trade("BINANCE:BTCUSDT", 100.0, 0.5, 1000));
        assertThat(client.status().tradesReceived()).isEqualTo(1);
        assertThat(client.status().lastTradeAt()).isEqualTo(5_000L);
    }

    @Test
    void pingsDoNotReachListeners() {
        List<Trade> received = new ArrayList<>();
        FinnhubStreamClient client = client(properties("key", true), List.of(received::addAll));

        client.handleMessage("{\"type\":\"ping\"}");

        assertThat(received).isEmpty();
        assertThat(client.status().lastTradeAt()).isNull();
    }

    @Test
    void refusesToStartWithoutAnApiKey() {
        FinnhubStreamClient client = client(properties("  ", true), List.of());

        assertThatThrownBy(client::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FINNHUB_API_KEY");
    }

    @Test
    void disabledStreamDoesNotConnect() {
        FinnhubStreamClient client = client(properties("", false), List.of());

        client.start();

        assertThat(client.isRunning()).isFalse();
        assertThat(client.status().enabled()).isFalse();
        assertThat(client.status().connected()).isFalse();
    }

    private static FinnhubProperties properties(String apiKey, boolean enabled) {
        return new FinnhubProperties(apiKey, URI.create("wss://example.invalid"), List.of("BINANCE:BTCUSDT"),
                enabled, Duration.ofSeconds(60));
    }

    private static FinnhubStreamClient client(FinnhubProperties properties, List<TradeListener> listeners) {
        ObjectMapper mapper = new ObjectMapper();
        return new FinnhubStreamClient(properties, new FinnhubMessageParser(mapper), listeners, mapper, CLOCK);
    }
}
