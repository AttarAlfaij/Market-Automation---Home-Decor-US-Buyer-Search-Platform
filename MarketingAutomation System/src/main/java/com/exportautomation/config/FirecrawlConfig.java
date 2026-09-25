package com.exportautomation.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class FirecrawlConfig {

    private static final Logger logger =
            LoggerFactory.getLogger(FirecrawlConfig.class);

    @Value("${firecrawl.api.key:}")
    private String apiKey;

    @Bean(name = "firecrawlWebClient")
    public WebClient firecrawlWebClient() {
        // Firecrawl searches can take a few seconds; generous timeouts so a
        // legitimate search is not cut short, while a stuck host still fails fast.
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .responseTimeout(Duration.ofSeconds(30))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                            .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));

        return WebClient.builder()
                .baseUrl("https://api.firecrawl.dev")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultStatusHandler(
                        status -> status.isError(),
                        this::handleError
                )
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
    }

    private Mono<? extends Throwable> handleError(ClientResponse response) {

        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {

                    logger.error(
                            "Firecrawl API HTTP error: status={}, body={}",
                            response.statusCode(),
                            body
                    );

                    return new RuntimeException(
                            "Firecrawl API HTTP error: "
                                    + response.statusCode()
                                    + " - "
                                    + body
                    );
                });
    }

    public String getApiKey() {
        return apiKey;
    }
}