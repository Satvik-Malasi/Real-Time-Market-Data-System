package com.example.websocket.websocket_Data_Connection.Websocket;

import com.example.websocket.websocket_Data_Connection.model.stockUpdate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Plain unit test: no Spring context and no network, so it runs without an API key
class StockWebsocketClientParseTest {

    @Test
    void keepsFractionalVolume() throws Exception {
        String message = "{\"type\":\"trade\",\"data\":["
                + "{\"p\":81147.94,\"s\":\"BINANCE:BTCUSDT\",\"t\":1792034811461,\"v\":0.00042},"
                + "{\"p\":81148.10,\"s\":\"BINANCE:BTCUSDT\",\"t\":1792034811572,\"v\":1.5}]}";

        List<stockUpdate> trades = stockWebsocketClient.parseTrades(message);

        assertEquals(2, trades.size());
        assertEquals(81147.94, trades.get(0).getPrice());
        assertEquals("BINANCE:BTCUSDT", trades.get(0).getSymbol());
        assertEquals(1792034811461L, trades.get(0).getTime());
        assertEquals(0.00042, trades.get(0).getVolume()); // was truncated to 0 when volume was an int
        assertEquals(1.5, trades.get(1).getVolume());
    }

    @Test
    void pingMessageHasNoTrades() throws Exception {
        assertTrue(stockWebsocketClient.parseTrades("{\"type\":\"ping\"}").isEmpty());
    }
}
