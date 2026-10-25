package com.example.websocket.websocket_Data_Connection.analysis;

/**
 * Result of {@link MarketAnalytics#compare}.
 *
 * @param comparedIntervals         intervals present in both candle lists
 * @param meanAbsCloseDiffPercent   average |our close - reference close| / reference close, in %
 * @param maxAbsCloseDiffPercent    the worst interval, in %
 * @param volumeCoveragePercent     our total volume / reference total volume, in %.
 *                                  Below 100 means the feed delivered only part of the trades.
 */
public record CandleComparison(
        int comparedIntervals,
        double meanAbsCloseDiffPercent,
        double maxAbsCloseDiffPercent,
        double volumeCoveragePercent) {
}
