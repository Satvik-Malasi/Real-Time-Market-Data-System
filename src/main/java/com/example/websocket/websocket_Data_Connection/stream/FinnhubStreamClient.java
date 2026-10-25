package com.example.websocket.websocket_Data_Connection.stream;

import com.example.websocket.websocket_Data_Connection.config.FinnhubProperties;
import com.example.websocket.websocket_Data_Connection.model.Trade;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps one WebSocket connection to Finnhub open, subscribes to the configured symbols,
 * and hands every trade batch to all {@link TradeListener}s.
 *
 * <p>Design points:
 * <ul>
 *   <li><b>Lifecycle:</b> {@link SmartLifecycle} makes Spring call {@link #start()} once every
 *       bean (including all listeners) exists, and {@link #stop()} on shutdown. Starting a
 *       connection from {@code @PostConstruct} instead would run before the app is ready.</li>
 *   <li><b>Reconnect:</b> when the connection closes for any reason (network drop, server
 *       restart, a failed attempt) a new one is scheduled after 1, 2, 4 ... seconds, capped at
 *       {@code finnhub.max-reconnect-delay}. Each attempt uses a fresh {@link Connection}, and
 *       the scheduling happens on a separate thread because Java-WebSocket does not allow
 *       reconnecting from inside its own callbacks.</li>
 *   <li><b>Secrets:</b> the API key is part of the URL, so the URL is never logged.</li>
 * </ul>
 */
@Component
public class FinnhubStreamClient implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FinnhubStreamClient.class);

    /** If no message (not even a ping) arrives for this long, the library closes the connection. */
    private static final int CONNECTION_LOST_TIMEOUT_SECONDS = 30;

    private final FinnhubProperties properties;
    private final FinnhubMessageParser parser;
    private final List<TradeListener> listeners;
    private final ObjectMapper mapper;
    private final Clock clock;

    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "finnhub-reconnect");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running;
    private volatile Connection connection;
    private volatile long lastTradeAt;
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final AtomicLong tradesReceived = new AtomicLong();

    public FinnhubStreamClient(FinnhubProperties properties,
                               FinnhubMessageParser parser,
                               List<TradeListener> listeners,
                               ObjectMapper mapper,
                               Clock clock) {
        this.properties = properties;
        this.parser = parser;
        this.listeners = List.copyOf(listeners);
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void start() {
        if (!properties.streamEnabled()) {
            log.info("Live stream disabled (finnhub.stream-enabled=false); not connecting to Finnhub");
            return;
        }
        if (!properties.hasApiKey()) {
            // Fail at startup with a clear message instead of retrying a connection that can never work.
            throw new IllegalStateException("No Finnhub API key. Set the FINNHUB_API_KEY environment variable, "
                    + "or put finnhub.api-key=<key> in application-local.properties (see README).");
        }
        running = true;
        openConnection();
    }

    @Override
    public void stop() {
        running = false;
        reconnectScheduler.shutdownNow();
        Connection current = connection;
        if (current != null) {
            current.close();
        }
        log.info("Live stream stopped after {} trades", tradesReceived.get());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public StreamStatus status() {
        Connection current = connection;
        long last = lastTradeAt;
        return new StreamStatus(
                properties.streamEnabled(),
                current != null && current.isOpen(),
                properties.symbols(),
                tradesReceived.get(),
                last == 0 ? null : last,
                reconnectAttempts.get());
    }

    /** Parses one raw message and gives the trades to every listener. Package-private for tests. */
    void handleMessage(String message) {
        List<Trade> trades = parser.parse(message);
        if (trades.isEmpty()) {
            return;
        }
        tradesReceived.addAndGet(trades.size());
        lastTradeAt = clock.millis();
        for (TradeListener listener : listeners) {
            try {
                listener.onTrades(trades);
            } catch (RuntimeException e) {
                // One failing consumer (e.g. a full disk for the CSV) must not stop the others.
                log.error("{} failed to handle {} trades", listener.getClass().getSimpleName(), trades.size(), e);
            }
        }
    }

    /** 1s, 2s, 4s, 8s ... never more than {@code max}. Package-private for tests. */
    static Duration backoffDelay(int attempt, Duration max) {
        Duration delay = Duration.ofSeconds(1L << Math.min(Math.max(attempt, 1) - 1, 30));
        return delay.compareTo(max) > 0 ? max : delay;
    }

    private void openConnection() {
        if (!running) {
            return;
        }
        URI uri = URI.create(properties.websocketUrl() + "?token="
                + URLEncoder.encode(properties.apiKey(), StandardCharsets.UTF_8));
        Connection next = new Connection(uri);
        next.setConnectionLostTimeout(CONNECTION_LOST_TIMEOUT_SECONDS);
        connection = next;
        log.info("Connecting to {} ...", properties.websocketUrl());
        next.connect();
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        Duration delay = backoffDelay(attempt, properties.maxReconnectDelay());
        log.warn("Reconnecting in {}s (attempt {})", delay.toSeconds(), attempt);
        try {
            reconnectScheduler.schedule(this::openConnection, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // stop() shut the scheduler down while this close was being handled: nothing to do.
        }
    }

    private void subscribe(WebSocketClient socket) {
        for (String symbol : properties.symbols()) {
            // Built with Jackson rather than string concatenation, so quotes in a symbol cannot break the JSON.
            String request = mapper.createObjectNode()
                    .put("type", "subscribe")
                    .put("symbol", symbol)
                    .toString();
            socket.send(request);
            log.info("Subscribed to {}", symbol);
        }
    }

    /** One physical connection. A new instance is made for every (re)connect. */
    private final class Connection extends WebSocketClient {

        Connection(URI uri) {
            super(uri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            log.info("Connected to Finnhub (HTTP {})", handshake.getHttpStatus());
            reconnectAttempts.set(0);
            subscribe(this);
        }

        @Override
        public void onMessage(String message) {
            handleMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            log.warn("Connection closed: code={} reason='{}' closedByServer={}", code, reason, remote);
            // Only the current connection reconnects; a late close from an old one is ignored.
            if (this == connection) {
                scheduleReconnect();
            }
        }

        @Override
        public void onError(Exception e) {
            // The library calls onClose after this, which handles the reconnect.
            log.warn("WebSocket error: {}", e.getMessage());
        }
    }
}
