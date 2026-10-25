package com.example.websocket.websocket_Data_Connection.stream;

import java.util.List;

/**
 * Health of the live feed, served at {@code /api/status}.
 *
 * @param lastTradeAt epoch millis of the last trade batch, or null if none yet
 */
public record StreamStatus(
        boolean enabled,
        boolean connected,
        List<String> symbols,
        long tradesReceived,
        Long lastTradeAt,
        int reconnectAttempts) {
}
