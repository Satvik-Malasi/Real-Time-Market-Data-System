package com.example.websocket.websocket_Data_Connection.realtime;

import com.example.websocket.websocket_Data_Connection.config.WebSocketConfig;
import com.example.websocket.websocket_Data_Connection.model.Trade;
import com.example.websocket.websocket_Data_Connection.stream.TradeListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Forwards live trades to every browser subscribed to {@code /topic/trades/<symbol>}.
 * This is the "real-time graph" part: the Finnhub WebSocket brings trades in, and this
 * pushes them out over the app's own WebSocket, so the page never has to poll.
 */
@Component
public class TradeBroadcaster implements TradeListener {

    private final SimpMessagingTemplate messaging;

    public TradeBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @Override
    public void onTrades(List<Trade> trades) {
        // One message per symbol and batch (not per trade) keeps the number of frames low.
        Map<String, List<Trade>> bySymbol = trades.stream()
                .collect(Collectors.groupingBy(Trade::symbol, LinkedHashMap::new, Collectors.toList()));
        bySymbol.forEach((symbol, batch) ->
                messaging.convertAndSend(WebSocketConfig.TRADES_TOPIC_PREFIX + symbol, batch));
    }
}
