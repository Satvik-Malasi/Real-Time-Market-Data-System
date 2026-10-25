package com.example.websocket.websocket_Data_Connection.storage;

import com.example.websocket.websocket_Data_Connection.config.StorageProperties;
import com.example.websocket.websocket_Data_Connection.model.Trade;
import com.example.websocket.websocket_Data_Connection.stream.TradeListener;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Appends every live trade to a CSV file: {@code symbol,price,volume,timestamp}.
 *
 * <p>The file stays open and is flushed once per WebSocket message. Opening and closing it
 * for every trade (as the first version did) costs a system call per trade; never flushing
 * would lose buffered trades if the app crashed. One flush per batch sits between the two.
 *
 * <p>CSV keeps the project dependency-free and the data easy to open in Excel or pandas.
 * For months of data or concurrent readers, a database (SQLite, PostgreSQL/TimescaleDB)
 * would be the next step.
 */
@Component
public class CsvTradeStore implements TradeListener {

    private static final Logger log = LoggerFactory.getLogger(CsvTradeStore.class);

    public static final String HEADER = "symbol,price,volume,timestamp";

    private final Path path;
    private BufferedWriter writer; // opened on the first write; guarded by "this"

    @Autowired
    public CsvTradeStore(StorageProperties properties) {
        this(properties.csvPath());
    }

    public CsvTradeStore(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    @Override
    public synchronized void onTrades(List<Trade> trades) {
        try {
            BufferedWriter out = writer();
            for (Trade trade : trades) {
                out.write(toCsvLine(trade));
                out.write('\n');
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write trades to " + path, e);
        }
    }

    /** Every trade recorded so far, oldest first. Malformed lines are skipped. */
    public synchronized List<Trade> readAll() {
        try {
            if (writer != null) {
                writer.flush();
            }
            return read(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read trades from " + path, e);
        }
    }

    @PreDestroy
    public synchronized void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    public static List<Trade> read(Path path) throws IOException {
        List<Trade> trades = new ArrayList<>();
        if (Files.notExists(path)) {
            return trades;
        }
        int skipped = 0;
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank() || line.equals(HEADER)) {
                    continue;
                }
                Trade trade = parseLine(line);
                if (trade == null) {
                    skipped++;
                } else {
                    trades.add(trade);
                }
            }
        }
        if (skipped > 0) {
            log.warn("Skipped {} malformed lines in {}", skipped, path);
        }
        return trades;
    }

    static String toCsvLine(Trade trade) {
        return trade.symbol() + ","
                + formatNumber(trade.price()) + ","
                + formatNumber(trade.volume()) + ","
                + trade.timestamp();
    }

    /**
     * Writes 0.00042 rather than Java's default 4.2E-4, and 81148 rather than 81148.0, so
     * every tool reads the number the same way. stripTrailingZeros is needed because
     * BigDecimal.valueOf(0.0001) has the value 0.00010.
     */
    public static String formatNumber(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    static Trade parseLine(String line) {
        String[] parts = line.split(",", -1);
        if (parts.length != 4) {
            return null;
        }
        try {
            return new Trade(parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Long.parseLong(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BufferedWriter writer() throws IOException {
        if (writer == null) {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            boolean isNewFile = Files.notExists(path) || Files.size(path) == 0;
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (isNewFile) {
                writer.write(HEADER);
                writer.write('\n');
            }
            log.info("Writing trades to {}", path.toAbsolutePath());
        }
        return writer;
    }
}
