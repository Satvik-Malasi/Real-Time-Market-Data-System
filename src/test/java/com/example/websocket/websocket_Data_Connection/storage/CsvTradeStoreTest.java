package com.example.websocket.websocket_Data_Connection.storage;

import com.example.websocket.websocket_Data_Connection.model.Trade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTradeStoreTest {

    @TempDir
    Path dir;

    @Test
    void writesHeaderOnceAndAppendsTrades() throws Exception {
        Path file = dir.resolve("sub/trades.csv"); // parent folder does not exist yet
        CsvTradeStore store = new CsvTradeStore(file);

        store.onTrades(List.of(new Trade("BINANCE:BTCUSDT", 81147.94, 0.00042, 1792034811461L)));
        store.onTrades(List.of(new Trade("BINANCE:BTCUSDT", 81148.0, 1.5, 1792034811572L)));
        store.close();

        assertThat(Files.readAllLines(file)).containsExactly(
                CsvTradeStore.HEADER,
                "BINANCE:BTCUSDT,81147.94,0.00042,1792034811461",
                "BINANCE:BTCUSDT,81148,1.5,1792034811572");
    }

    @Test
    void reopeningAnExistingFileDoesNotRepeatTheHeader() throws Exception {
        Path file = dir.resolve("trades.csv");
        CsvTradeStore first = new CsvTradeStore(file);
        first.onTrades(List.of(new Trade("A", 1, 1, 1)));
        first.close();

        CsvTradeStore second = new CsvTradeStore(file);
        second.onTrades(List.of(new Trade("A", 2, 2, 2)));
        second.close();

        assertThat(Files.readAllLines(file)).filteredOn(CsvTradeStore.HEADER::equals).hasSize(1);
        assertThat(CsvTradeStore.read(file)).hasSize(2);
    }

    @Test
    void readAllReturnsWhatWasWritten() throws Exception {
        CsvTradeStore store = new CsvTradeStore(dir.resolve("trades.csv"));
        List<Trade> trades = List.of(
                new Trade("BINANCE:BTCUSDT", 81147.94, 0.00042, 1L),
                new Trade("BINANCE:BTCUSDT", 81148.5, 2.25, 2L));

        store.onTrades(trades);

        assertThat(store.readAll()).isEqualTo(trades);
        store.close();
    }

    @Test
    void smallVolumesAreNotWrittenInScientificNotation() {
        assertThat(CsvTradeStore.toCsvLine(new Trade("X", 0.0001, 0.00000123, 7)))
                .isEqualTo("X,0.0001,0.00000123,7");
    }

    @Test
    void readSkipsMalformedLines() throws Exception {
        Path file = dir.resolve("mixed.csv");
        Files.writeString(file, """
                symbol,price,volume,timestamp
                BINANCE:BTCUSDT,100.0,1.0,1
                garbage
                BINANCE:BTCUSDT,abc,1.0,2
                BINANCE:BTCUSDT,101.0,2.0,3
                """);

        assertThat(CsvTradeStore.read(file)).containsExactly(
                new Trade("BINANCE:BTCUSDT", 100.0, 1.0, 1),
                new Trade("BINANCE:BTCUSDT", 101.0, 2.0, 3));
    }

    @Test
    void readingAMissingFileGivesNoTrades() throws Exception {
        assertThat(CsvTradeStore.read(dir.resolve("missing.csv"))).isEmpty();
    }
}
