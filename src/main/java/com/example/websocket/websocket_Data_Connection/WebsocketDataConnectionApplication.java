package com.example.websocket.websocket_Data_Connection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WebsocketDataConnectionApplication {

	public static void main(String[] args) {
		SpringApplication.run(WebsocketDataConnectionApplication.class, args);
	}

	// Classes that need "now" take a Clock instead of calling System.currentTimeMillis(),
	// so tests can use a fixed time.
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
