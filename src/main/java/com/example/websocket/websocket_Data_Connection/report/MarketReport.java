package com.example.websocket.websocket_Data_Connection.report;

/**
 * Summary of the live trades in a time window, served at {@code /api/report}.
 * Price fields are null when the window has no trades; statistics are null when there
 * is not enough data to compute them.
 *
 * @param oneMinuteVolatilityPercent std. deviation of one-minute log returns, in %
 * @param movingAverage5m            mean of the last five one-minute closes
 */
public record MarketReport(
        String symbol,
        long fromTime,
        long toTime,
        int tradeCount,
        double totalVolume,
        Double firstPrice,
        Double lastPrice,
        Double highPrice,
        Double lowPrice,
        Double changePercent,
        Double vwap,
        Double oneMinuteVolatilityPercent,
        Double movingAverage5m) {
}
