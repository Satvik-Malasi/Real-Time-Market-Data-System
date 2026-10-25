package com.example.websocket.websocket_Data_Connection.storage;

import com.example.websocket.websocket_Data_Connection.config.StorageProperties;
import com.example.websocket.websocket_Data_Connection.model.Trade;
import com.example.websocket.websocket_Data_Connection.stream.TradeListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Keeps the most recent trades in memory so the report and chart endpoints answer quickly
 * without re-reading the CSV file on every request.
 *
 * <p>Bounded two ways, so memory cannot grow forever: trades older than
 * {@code storage.memory-retention} are dropped, and so are the oldest ones once there are
 * more than {@code storage.memory-max-trades}.
 */
@Component
public class RecentTradeBuffer implements TradeListener {

    private final Deque<Trade> trades = new ArrayDeque<>(); // oldest first; guarded by "this"
    private final Duration retention;
    private final int maxTrades;
    private final Clock clock;

    @Autowired // two constructors: tell Spring which one to use
    public RecentTradeBuffer(StorageProperties properties, Clock clock) {
        this(properties.memoryRetention(), properties.memoryMaxTrades(), clock);
    }

    RecentTradeBuffer(Duration retention, int maxTrades, Clock clock) {
        if (maxTrades < 1) {
            throw new IllegalArgumentException("storage.memory-max-trades must be at least 1");
        }
        this.retention = retention;
        this.maxTrades = maxTrades;
        this.clock = clock;
    }

    @Override
    public synchronized void onTrades(List<Trade> batch) {
        trades.addAll(batch);
        evictOld();
    }

    /** Trades for {@code symbol} with a timestamp at or after {@code fromMillis}, oldest first. */
    public synchronized List<Trade> since(String symbol, long fromMillis) {
        evictOld();
        List<Trade> result = new ArrayList<>();
        for (Trade trade : trades) {
            if (trade.timestamp() >= fromMillis && trade.symbol().equals(symbol)) {
                result.add(trade);
            }
        }
        return result;
    }

    public synchronized int size() {
        return trades.size();
    }

    public Duration retention() {
        return retention;
    }

    private void evictOld() {
        long cutoff = clock.millis() - retention.toMillis();
        // Trades arrive in (nearly) time order, so the oldest are at the head of the queue.
        while (!trades.isEmpty() && (trades.size() > maxTrades || trades.peekFirst().timestamp() < cutoff)) {
            trades.pollFirst();
        }
    }
}
