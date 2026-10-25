package com.example.websocket.websocket_Data_Connection;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole app starts and serves the dashboard. The Finnhub feed is switched off, so this
 * runs without an API key or internet (the first version of this test connected to Finnhub).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"finnhub.stream-enabled=false",
		"storage.csv-path=target/test-data/trades.csv"})
class WebsocketDataConnectionApplicationTests {

	@Autowired
	TestRestTemplate http;

	@Test
	void servesTheDashboardAndTheApi() {
		assertThat(http.getForObject("/", String.class)).contains("Live Market Feed");
		assertThat(http.getForObject("/api/status", String.class)).contains("\"enabled\":false");
	}
}
