package com.example.websocket.websocket_Data_Connection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Settings for the live Finnhub feed, bound from the {@code finnhub.*} properties.
 * Using a typed record instead of scattered {@code @Value} fields means every setting
 * is documented in one place and checked once at startup.
 */
@ConfigurationProperties(prefix = "finnhub")
public record FinnhubProperties(
        String apiKey,
        URI websocketUrl,
        List<String> symbols,
        boolean streamEnabled,
        Duration maxReconnectDelay) {

    public FinnhubProperties {
        if (symbols == null || symbols.isEmpty()) {
            throw new IllegalArgumentException("finnhub.symbols must list at least one symbol");
        }
        symbols = List.copyOf(symbols);
    }

    public String defaultSymbol() {
        return symbols.getFirst();
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
