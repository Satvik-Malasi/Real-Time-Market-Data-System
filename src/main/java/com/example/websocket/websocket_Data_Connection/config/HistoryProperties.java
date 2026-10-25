package com.example.websocket.websocket_Data_Connection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * Historical candles come from Binance's public API. Finnhub's candle endpoints need a
 * paid plan (they answer 403 on the free tier), while Binance's need no key.
 */
@ConfigurationProperties(prefix = "history")
public record HistoryProperties(URI binanceBaseUrl) {
}
