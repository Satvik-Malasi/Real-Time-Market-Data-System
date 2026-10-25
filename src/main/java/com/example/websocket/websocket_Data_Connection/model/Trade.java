package com.example.websocket.websocket_Data_Connection.model;

/**
 * One executed trade.
 *
 * @param symbol    e.g. {@code BINANCE:BTCUSDT}
 * @param price     price per unit
 * @param volume    units traded; a double because crypto trades are fractional (0.0004 BTC)
 * @param timestamp when it happened, in milliseconds since the Unix epoch (UTC)
 */
public record Trade(String symbol, double price, double volume, long timestamp) {
}
