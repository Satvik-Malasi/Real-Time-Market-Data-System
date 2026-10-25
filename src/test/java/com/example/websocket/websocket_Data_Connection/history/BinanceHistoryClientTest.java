package com.example.websocket.websocket_Data_Connection.history;

import com.example.websocket.websocket_Data_Connection.config.HistoryProperties;
import com.example.websocket.websocket_Data_Connection.model.Candle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Uses a fake HTTP server (MockRestServiceServer), so no request reaches Binance. */
class BinanceHistoryClientTest {

    private MockRestServiceServer server;
    private BinanceHistoryClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BinanceHistoryClient(builder, new HistoryProperties(URI.create("https://binance.test")));
    }

    @Test
    void mapsKlineArraysToCandles() {
        // Shape copied from a real response: prices and volumes are strings, field 8 is the trade count.
        server.expect(requestTo("https://binance.test/api/v3/klines?symbol=BTCUSDT&interval=1m&limit=2&startTime=1792403400000"))
                .andRespond(withSuccess("""
                        [[1792403400000,"83005.59","83005.59","82992.00","82992.00","1.68206",1792403459999,"139613.09",561,"0.61","50671.71","0"],
                         [1792403460000,"82992.00","82992.00","82976.67","82976.67","0.34001",1792403519999,"28215.79",348,"0.0003","25.72","0"]]
                        """, MediaType.APPLICATION_JSON));

        List<Candle> candles = client.fetchCandles("BINANCE:BTCUSDT", "1m", 2, 1792403400000L);

        assertThat(candles).containsExactly(
                new Candle("BINANCE:BTCUSDT", 1792403400000L, 83005.59, 83005.59, 82992.00, 82992.00, 1.68206, 561),
                new Candle("BINANCE:BTCUSDT", 1792403460000L, 82992.00, 82992.00, 82976.67, 82976.67, 0.34001, 348));
        server.verify();
    }

    @Test
    void serverErrorsBecomeHistoryUnavailable() {
        server.expect(requestTo("https://binance.test/api/v3/klines?symbol=BTCUSDT&interval=1h&limit=5"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchCandles("BINANCE:BTCUSDT", "1h", 5))
                .isInstanceOf(HistoryUnavailableException.class)
                .hasMessageContaining("BTCUSDT");
    }

    @Test
    void rejectsBadParametersBeforeCallingBinance() {
        assertThatThrownBy(() -> client.fetchCandles("BINANCE:BTCUSDT", "7m", 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.fetchCandles("BINANCE:BTCUSDT", "1m", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.fetchCandles("BINANCE:BTCUSDT", "1m", 1001))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify(); // no request was made
    }

    @Test
    void convertsFinnhubSymbolsToBinancePairs() {
        assertThat(BinanceHistoryClient.toBinancePair("BINANCE:BTCUSDT")).isEqualTo("BTCUSDT");
        assertThat(BinanceHistoryClient.toBinancePair("ethusdt")).isEqualTo("ETHUSDT");
        assertThatThrownBy(() -> BinanceHistoryClient.toBinancePair("COINBASE:BTC-USD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BinanceHistoryClient.toBinancePair("BTC&limit=5"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
