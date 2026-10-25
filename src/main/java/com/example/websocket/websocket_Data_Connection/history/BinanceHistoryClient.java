package com.example.websocket.websocket_Data_Connection.history;

import com.example.websocket.websocket_Data_Connection.config.HistoryProperties;
import com.example.websocket.websocket_Data_Connection.model.Candle;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Historical candles from Binance's public REST API ({@code GET /api/v3/klines}, no key).
 *
 * <p>Each candle comes back as a JSON array:
 * {@code [openTime, "open", "high", "low", "close", "volume", closeTime, "quoteVolume", trades, ...]}.
 * Prices are strings so that no precision is lost in transit.
 */
@Component
public class BinanceHistoryClient {

    public static final Set<String> INTERVALS = Set.of("1m", "3m", "5m", "15m", "30m", "1h", "4h", "1d");
    public static final int MAX_LIMIT = 1000;

    private final RestClient restClient;

    public BinanceHistoryClient(RestClient.Builder builder, HistoryProperties properties) {
        this.restClient = builder.baseUrl(properties.binanceBaseUrl().toString()).build();
    }

    /** The latest {@code limit} candles. */
    public List<Candle> fetchCandles(String symbol, String interval, int limit) {
        return fetchCandles(symbol, interval, limit, null);
    }

    /**
     * Up to {@code limit} candles, starting at {@code startTime} (epoch millis) if given.
     *
     * @param symbol a Finnhub-style symbol such as {@code BINANCE:BTCUSDT}, or a plain pair {@code BTCUSDT}
     */
    public List<Candle> fetchCandles(String symbol, String interval, int limit, Long startTime) {
        if (!INTERVALS.contains(interval)) {
            throw new IllegalArgumentException("interval must be one of " + INTERVALS);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        String pair = toBinancePair(symbol);

        JsonNode body;
        try {
            body = restClient.get()
                    .uri(uri -> {
                        uri.path("/api/v3/klines")
                                .queryParam("symbol", pair)
                                .queryParam("interval", interval)
                                .queryParam("limit", limit);
                        if (startTime != null) {
                            uri.queryParam("startTime", startTime);
                        }
                        return uri.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new HistoryUnavailableException("Binance request for " + pair + " failed: " + e.getMessage(), e);
        }
        if (body == null || !body.isArray()) {
            throw new HistoryUnavailableException("Binance returned an unexpected response for " + pair, null);
        }

        List<Candle> candles = new ArrayList<>(body.size());
        for (JsonNode k : body) {
            candles.add(new Candle(
                    symbol,
                    k.get(0).asLong(),
                    Double.parseDouble(k.get(1).asText()),
                    Double.parseDouble(k.get(2).asText()),
                    Double.parseDouble(k.get(3).asText()),
                    Double.parseDouble(k.get(4).asText()),
                    Double.parseDouble(k.get(5).asText()),
                    k.get(8).asInt()));
        }
        return candles;
    }

    /** {@code BINANCE:BTCUSDT} becomes {@code BTCUSDT}. Symbols from other exchanges are rejected. */
    static String toBinancePair(String symbol) {
        String value = symbol.trim().toUpperCase(Locale.ROOT);
        int colon = value.indexOf(':');
        if (colon >= 0) {
            if (!value.substring(0, colon).equals("BINANCE")) {
                throw new IllegalArgumentException("Historical data is only available for BINANCE:* symbols, not " + symbol);
            }
            value = value.substring(colon + 1);
        }
        if (!value.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("Not a valid trading pair: " + symbol);
        }
        return value;
    }
}
