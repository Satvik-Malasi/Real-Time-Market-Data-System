package com.example.websocket.websocket_Data_Connection.stream;

import com.example.websocket.websocket_Data_Connection.model.Trade;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one Finnhub WebSocket message into trades.
 *
 * <p>Finnhub sends three kinds of message:
 * <pre>
 * {"type":"trade","data":[{"p":81147.94,"s":"BINANCE:BTCUSDT","t":1792034811461,"v":0.00042,"c":null}]}
 * {"type":"ping"}
 * {"type":"error","msg":"Invalid symbol"}
 * </pre>
 * Kept separate from the connection code so it can be unit-tested without a network.
 * A bad message or a bad trade entry is logged and skipped: one malformed row must not
 * stop the whole feed.
 */
@Component
public class FinnhubMessageParser {

    private static final Logger log = LoggerFactory.getLogger(FinnhubMessageParser.class);

    private final ObjectMapper mapper;

    public FinnhubMessageParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<Trade> parse(String message) {
        JsonNode root;
        try {
            root = mapper.readTree(message);
        } catch (JsonProcessingException e) {
            log.warn("Ignoring message that is not valid JSON: {}", abbreviate(message));
            return List.of();
        }

        String type = root.path("type").asText("");
        switch (type) {
            case "trade":
                return parseTrades(root.path("data"));
            case "ping":
                return List.of();
            case "error":
                log.error("Finnhub reported an error: {}", root.path("msg").asText());
                return List.of();
            default:
                log.debug("Ignoring message of type '{}'", type);
                return List.of();
        }
    }

    private List<Trade> parseTrades(JsonNode data) {
        List<Trade> trades = new ArrayList<>(data.size());
        for (JsonNode node : data) {
            if (!isValidTrade(node)) {
                log.warn("Skipping malformed trade entry: {}", node);
                continue;
            }
            trades.add(new Trade(
                    node.get("s").asText(),
                    node.get("p").asDouble(),
                    node.get("v").asDouble(),
                    node.get("t").asLong()));
        }
        return trades;
    }

    private static boolean isValidTrade(JsonNode node) {
        return node.path("s").isTextual()
                && node.path("p").isNumber()
                && node.path("v").isNumber()
                && node.path("t").isIntegralNumber()
                && node.get("p").asDouble() > 0
                && node.get("v").asDouble() >= 0;
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
