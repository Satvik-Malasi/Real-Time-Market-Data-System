package com.example.websocket.websocket_Data_Connection.web;

import com.example.websocket.websocket_Data_Connection.config.FinnhubProperties;
import com.example.websocket.websocket_Data_Connection.config.StorageProperties;
import com.example.websocket.websocket_Data_Connection.history.BinanceHistoryClient;
import com.example.websocket.websocket_Data_Connection.history.HistoryUnavailableException;
import com.example.websocket.websocket_Data_Connection.report.MarketReport;
import com.example.websocket.websocket_Data_Connection.report.ReportService;
import com.example.websocket.websocket_Data_Connection.stream.FinnhubStreamClient;
import com.example.websocket.websocket_Data_Connection.stream.StreamStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests only the HTTP layer: the services behind it are mocks. */
@WebMvcTest(MarketController.class)
@Import(MarketControllerTest.Config.class)
class MarketControllerTest {

    @TestConfiguration
    @EnableConfigurationProperties({FinnhubProperties.class, StorageProperties.class})
    static class Config {
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FinnhubStreamClient stream;
    @MockitoBean
    ReportService reports;
    @MockitoBean
    BinanceHistoryClient history;

    @Test
    void statusReportsTheStream() throws Exception {
        when(stream.status()).thenReturn(new StreamStatus(true, true, List.of("BINANCE:BTCUSDT"), 42, 1000L, 0));

        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.tradesReceived").value(42));
    }

    @Test
    void reportUsesTheDefaultSymbol() throws Exception {
        when(reports.summary("BINANCE:BTCUSDT", 15)).thenReturn(
                new MarketReport("BINANCE:BTCUSDT", 0, 1, 0, 0, null, null, null, null, null, null, null, null));

        mvc.perform(get("/api/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("BINANCE:BTCUSDT"))
                .andExpect(jsonPath("$.lastPrice").isEmpty());
    }

    @Test
    void unknownSymbolIsABadRequest() throws Exception {
        mvc.perform(get("/api/report").param("symbol", "NASDAQ:AAPL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Not a subscribed symbol")));
    }

    @Test
    void minutesOutsideTheRetentionAreRejected() throws Exception {
        mvc.perform(get("/api/report").param("minutes", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/report").param("minutes", "121")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/report").param("minutes", "abc")).andExpect(status().isBadRequest());
    }

    @Test
    void csvDownloadIsAnAttachment() throws Exception {
        when(reports.candlesCsv("BINANCE:BTCUSDT", 60)).thenReturn("open_time_utc,open\n");

        mvc.perform(get("/api/candles.csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"BINANCE_BTCUSDT_1m_candles.csv\""));
    }

    @Test
    void historyFailureIsABadGateway() throws Exception {
        when(history.fetchCandles(eq("BINANCE:BTCUSDT"), anyString(), anyInt()))
                .thenThrow(new HistoryUnavailableException("Binance request for BTCUSDT failed", null));

        mvc.perform(get("/api/history"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Binance request for BTCUSDT failed"));
    }
}
