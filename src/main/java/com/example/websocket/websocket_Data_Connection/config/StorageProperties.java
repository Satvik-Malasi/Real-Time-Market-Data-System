package com.example.websocket.websocket_Data_Connection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/** Where trades are written ({@code csvPath}) and how many are kept in memory. */
@ConfigurationProperties(prefix = "storage")
public record StorageProperties(Path csvPath, Duration memoryRetention, int memoryMaxTrades) {
}
