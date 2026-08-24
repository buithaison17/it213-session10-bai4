package com.example.bai4.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(LangfuseProperties.class)
public class LangfuseConfig {

    private final LangfuseProperties properties;

    public LangfuseConfig(LangfuseProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RestClient langfuseRestClient() {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeaders(headers -> headers.setBasicAuth(
                        properties.getPublicKey(),
                        properties.getSecretKey()
                ))
                .build();
    }
}
