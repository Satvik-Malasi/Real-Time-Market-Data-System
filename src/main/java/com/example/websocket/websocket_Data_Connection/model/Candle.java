package com.example.websocket.websocket_Data_Connection.model;

/**
 * Trades summarised over one time interval (an "OHLC" bar).
 *
 * @param openTime   start of the interval, epoch milliseconds (UTC)
 * @param tradeCount number of trades in the interval
 */
public record Candle(
        String symbol,
        long openTime,
        double open,
        double high,
        double low,
        double close,
        double volume,
        int tradeCount) {
}
