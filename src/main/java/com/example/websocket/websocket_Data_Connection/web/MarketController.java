package com.example.websocket.websocket_Data_Connection.web;

import com.example.websocket.websocket_Data_Connection.config.FinnhubProperties;
import com.example.websocket.websocket_Data_Connection.config.StorageProperties;
import com.example.websocket.websocket_Data_Connection.history.BinanceHistoryClient;
import com.example.websocket.websocket_Data_Connection.model.Candle;
import com.example.websocket.websocket_Data_Connection.report.DataQualityReport;
import com.example.websocket.websocket_Data_Connection.report.MarketReport;
import com.example.websocket.websocket_Data_Connection.report.ReportService;
import com.example.websocket.websocket_Data_Connection.stream.FinnhubStreamClient;
import com.example.websocket.websocket_Data_Connection.stream.StreamStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * The HTTP API. It only checks and translates parameters; the work happens in
 * {@link ReportService} and {@link BinanceHistoryClient}. Invalid parameters throw
 * {@link IllegalArgumentException}, which {@link ApiExceptionHandler} turns into a 400.
 */
@RestController
@RequestMapping("/api")
public class MarketController {

    private final FinnhubStreamClient stream;
    private final ReportService reports;
    private final BinanceHistoryClient history;
    private final FinnhubProperties finnhub;
    private final int maxMinutes;

    public MarketController(FinnhubStreamClient stream,
                            ReportService reports,
                            BinanceHistoryClient history,
                            FinnhubProperties finnhub,
                            StorageProperties storage) {
        this.stream = stream;
        this.reports = reports;
        this.history = history;
        this.finnhub = finnhub;
        // Live data only goes back as far as the in-memory buffer keeps it.
        this.maxMinutes = (int) storage.memoryRetention().toMinutes();
    }

    @GetMapping("/status")
    public StreamStatus status() {
        return stream.status();
    }

    @GetMapping("/report")
    public MarketReport report(@RequestParam(required = false) String symbol,
                               @RequestParam(defaultValue = "15") int minutes) {
        return reports.summary(liveSymbol(symbol), checkMinutes(minutes));
    }

    @GetMapping("/candles")
    public List<Candle> candles(@RequestParam(required = false) String symbol,
                                @RequestParam(defaultValue = "60") int minutes,
                                @RequestParam(defaultValue = "1") int intervalMinutes) {
        if (intervalMinutes < 1 || intervalMinutes > 60) {
            throw new IllegalArgumentException("intervalMinutes must be between 1 and 60");
        }
        return reports.candles(liveSymbol(symbol), checkMinutes(minutes), Duration.ofMinutes(intervalMinutes));
    }

    @GetMapping("/candles.csv")
    public ResponseEntity<String> candlesCsv(@RequestParam(required = false) String symbol,
                                             @RequestParam(defaultValue = "60") int minutes) {
        String checkedSymbol = liveSymbol(symbol);
        String fileName = checkedSymbol.replaceAll("[^A-Za-z0-9]", "_") + "_1m_candles.csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(reports.candlesCsv(checkedSymbol, checkMinutes(minutes)));
    }

    @GetMapping("/history")
    public List<Candle> history(@RequestParam(required = false) String symbol,
                                @RequestParam(defaultValue = "1m") String interval,
                                @RequestParam(defaultValue = "60") int limit) {
        return history.fetchCandles(symbol == null ? finnhub.defaultSymbol() : symbol, interval, limit);
    }

    @GetMapping("/data-quality")
    public DataQualityReport dataQuality(@RequestParam(required = false) String symbol,
                                         @RequestParam(defaultValue = "15") int minutes) {
        return reports.dataQuality(liveSymbol(symbol), checkMinutes(minutes));
    }

    private String liveSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return finnhub.defaultSymbol();
        }
        if (!finnhub.symbols().contains(symbol)) {
            throw new IllegalArgumentException("Not a subscribed symbol: " + symbol + ". Subscribed: " + finnhub.symbols());
        }
        return symbol;
    }

    private int checkMinutes(int minutes) {
        if (minutes < 1 || minutes > maxMinutes) {
            throw new IllegalArgumentException("minutes must be between 1 and " + maxMinutes);
        }
        return minutes;
    }
}
