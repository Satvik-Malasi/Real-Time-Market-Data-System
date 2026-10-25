package com.example.websocket.websocket_Data_Connection.report;

/**
 * How well our live-built one-minute candles match the exchange's official ones,
 * served at {@code /api/data-quality}. Metric fields are null when no minute could be compared.
 */
public record DataQualityReport(
        String symbol,
        int comparedMinutes,
        Double meanAbsCloseDiffPercent,
        Double maxAbsCloseDiffPercent,
        Double volumeCoveragePercent,
        String note) {
}
