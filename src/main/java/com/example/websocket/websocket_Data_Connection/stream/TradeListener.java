package com.example.websocket.websocket_Data_Connection.stream;

import com.example.websocket.websocket_Data_Connection.model.Trade;

import java.util.List;

/**
 * Anything that wants the live trades: the CSV store, the in-memory buffer and the
 * browser broadcaster all implement this, and {@link FinnhubStreamClient} hands every
 * batch to each of them (the observer pattern). Adding a new consumer means writing one
 * more {@code @Component} that implements this interface; the client does not change.
 *
 * <p>Called on the WebSocket thread, so implementations must be quick and thread-safe.
 */
@FunctionalInterface
public interface TradeListener {

    void onTrades(List<Trade> trades);
}
