package com.example.websocket.websocket_Data_Connection.history;

/** The historical data provider could not be reached or gave an unusable answer. */
public class HistoryUnavailableException extends RuntimeException {

    public HistoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
