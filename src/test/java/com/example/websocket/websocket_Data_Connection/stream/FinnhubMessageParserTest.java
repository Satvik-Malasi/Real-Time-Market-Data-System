package com.example.websocket.websocket_Data_Connection.stream;

import com.example.websocket.websocket_Data_Connection.model.Trade;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinnhubMessageParserTest {

    private final FinnhubMessageParser parser = new FinnhubMessageParser(new ObjectMapper());

    @Test
    void parsesEveryTradeInAMessage() {
        String message = """
                {"type":"trade","data":[
                  {"p":81147.94,"s":"BINANCE:BTCUSDT","t":1792034811461,"v":0.00042,"c":null},
                  {"p":81148.10,"s":"BINANCE:BTCUSDT","t":1792034811572,"v":1.5}
                ]}""";

        List<Trade> trades = parser.parse(message);

        assertThat(trades).containsExactly(
                new Trade("BINANCE:BTCUSDT", 81147.94, 0.00042, 1792034811461L),
                new Trade("BINANCE:BTCUSDT", 81148.10, 1.5, 1792034811572L));
    }

    @Test
    void keepsFractionalVolume() {
        // The first version stored volume as an int, so this became 0.
        List<Trade> trades = parser.parse("""
                {"type":"trade","data":[{"p":1,"s":"X","t":1,"v":0.00042}]}""");

        assertThat(trades.getFirst().volume()).isEqualTo(0.00042);
    }

    @Test
    void pingAndErrorMessagesHaveNoTrades() {
        assertThat(parser.parse("{\"type\":\"ping\"}")).isEmpty();
        assertThat(parser.parse("{\"type\":\"error\",\"msg\":\"Invalid symbol\"}")).isEmpty();
    }

    @Test
    void invalidJsonIsIgnoredInsteadOfThrowing() {
        assertThat(parser.parse("not json at all")).isEmpty();
    }

    @Test
    void skipsMalformedEntriesButKeepsTheRest() {
        String message = """
                {"type":"trade","data":[
                  {"p":"oops","s":"BINANCE:BTCUSDT","t":1,"v":1},
                  {"s":"BINANCE:BTCUSDT","t":2,"v":1},
                  {"p":-5,"s":"BINANCE:BTCUSDT","t":3,"v":1},
                  {"p":100.5,"s":"BINANCE:BTCUSDT","t":4,"v":2}
                ]}""";

        assertThat(parser.parse(message)).containsExactly(new Trade("BINANCE:BTCUSDT", 100.5, 2, 4));
    }
}
