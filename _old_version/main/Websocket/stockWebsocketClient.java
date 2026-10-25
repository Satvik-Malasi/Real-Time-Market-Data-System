package com.example.websocket.websocket_Data_Connection.Websocket;

import com.example.websocket.websocket_Data_Connection.model.stockUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.websocket.websocket_Data_Connection.Service.CsvWriter;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class stockWebsocketClient extends WebSocketClient
{
    private static final ObjectMapper mapper=new ObjectMapper();
    private static final long MAX_RECONNECT_DELAY_SECONDS=60;

    private final CsvWriter csvWriter;
    private final List<String> symbol;

    // reconnect() must not be called from the WebSocket's own thread (inside onClose),
    // so reconnects are scheduled on this separate daemon thread instead
    private final ScheduledExecutorService reconnectScheduler=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread th=new Thread(r,"finnhub-reconnect");
        th.setDaemon(true);
        return th;
    });
    private int reconnectAttempts=0;
    private volatile boolean shuttingDown=false;

    public stockWebsocketClient(String apiKey,List<String> symbol, CsvWriter csvWriter) throws Exception
    {
        super(new URI("wss://ws.finnhub.io?token="+apiKey));
        this.csvWriter=csvWriter;
        this.symbol=symbol;
    }

    @Override
    public void onOpen(ServerHandshake handShake)
    {
        System.out.println("connected to finnhub...");
        reconnectAttempts=0;
        for(String str:symbol) {
            String msg = "{\"type\":\"subscribe\",\"symbol\":\"" + str + "\"}";
            this.send(msg);
            System.out.println("subscribed:" + str);
        }
    }
    @Override
    public void onMessage(String message) {
        System.out.println("recieved "+message);
        try{
            for (stockUpdate update : parseTrades(message)) {
                csvWriter.write(update);
            }
        }
        catch(Exception e)
        {
            System.out.println("failed to parse"+message+"|error "+e);
        }
    }

    /**
     * Turns one Finnhub message into trades. Messages without a "data" array
     * (e.g. {"type":"ping"}) give an empty list.
     */
    public static List<stockUpdate> parseTrades(String message) throws Exception
    {
        List<stockUpdate> trades=new ArrayList<>();
        JsonNode root = mapper.readTree(message);
        if (root.has("data")) {
            for (JsonNode t : root.get("data")) {
                double price = t.get("p").asDouble();
                String symbol = t.get("s").asText();
                long time = t.get("t").asLong();
                double volume = t.get("v").asDouble();

                trades.add(new stockUpdate(price, symbol, time, volume));
            }
        }
        return trades;
    }

    @Override
    public void onClose(int code,String reason, boolean remote){
        System.out.println("Connection closed. code=" + code + " reason=" + reason);
        // onClose also runs when a connect or reconnect attempt fails, so every failure schedules the next try
        if(!shuttingDown) {
            scheduleReconnect();
        }
    }

    @Override
    public void onError(Exception e){
        System.out.println("WebSocket error: " + e.getMessage());
    }

    // waits 1, 2, 4, 8 ... seconds between attempts, never more than MAX_RECONNECT_DELAY_SECONDS
    private void scheduleReconnect()
    {
        long delay=Math.min(MAX_RECONNECT_DELAY_SECONDS, 1L << Math.min(reconnectAttempts, 6));
        reconnectAttempts++;
        System.out.println("reconnecting in "+delay+"s (attempt "+reconnectAttempts+")");
        reconnectScheduler.schedule(this::reconnect, delay, TimeUnit.SECONDS);
    }

    /** Closes the connection for good: no reconnect attempts after this. */
    public void shutdown()
    {
        shuttingDown=true;
        reconnectScheduler.shutdownNow();
        this.close();
    }
}
