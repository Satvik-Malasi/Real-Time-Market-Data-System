package com.example.websocket.websocket_Data_Connection.realtime;

import com.example.websocket.websocket_Data_Connection.model.Trade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check of the browser side: starts the real server on a random port, connects
 * a STOMP client over a real WebSocket (as the dashboard does), and checks that trades
 * given to the broadcaster arrive. The Finnhub feed is switched off, so no key is needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "finnhub.stream-enabled=false",
        "storage.csv-path=target/test-data/trades.csv"})
class TradeBroadcastIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TradeBroadcaster broadcaster;

    @Test
    void subscribedClientReceivesTrades() throws Exception {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        StompSession session = stompClient
                .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() { })
                .get(5, TimeUnit.SECONDS);

        BlockingQueue<Trade[]> received = new LinkedBlockingQueue<>();
        session.subscribe("/topic/trades/BINANCE:BTCUSDT", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Trade[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((Trade[]) payload);
            }
        });

        Trade trade = new Trade("BINANCE:BTCUSDT", 81147.94, 0.00042, 1792034811461L);
        // The subscription is registered asynchronously, so publish until it arrives (max ~5s).
        Trade[] batch = null;
        for (int attempt = 0; attempt < 25 && batch == null; attempt++) {
            broadcaster.onTrades(List.of(trade, new Trade("BINANCE:ETHUSDT", 1, 1, 1)));
            batch = received.poll(200, TimeUnit.MILLISECONDS);
        }

        // Only the subscribed symbol's trade arrives.
        assertThat(batch).containsExactly(trade);
        session.disconnect();
        stompClient.stop();
    }
}
