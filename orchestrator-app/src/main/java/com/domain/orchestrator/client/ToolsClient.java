package com.domain.orchestrator.client;

import com.domain.shared.model.ConsumerHealthResponse;
import com.domain.shared.model.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;

@Component
public class ToolsClient {
    private final RestTemplate restTemplate;
    private final String consumerBaseUrl;

    public ToolsClient(RestTemplate restTemplate,
                       @Value("${consumer.base-url:http://localhost:8080}") String consumerBaseUrl) {
        this.restTemplate = restTemplate;
        this.consumerBaseUrl = consumerBaseUrl;
    }

    public ConsumerHealthResponse health() {
        return restTemplate.getForObject(consumerBaseUrl + "/api/health", ConsumerHealthResponse.class);
    }

    public List<ConsumerRecord> records() {
        ConsumerRecord[] response = restTemplate.getForObject(consumerBaseUrl + "/api/records", ConsumerRecord[].class);
        return response == null ? List.of() : Arrays.asList(response);
    }

    public ConsumerRecord record(long id) {
        return restTemplate.getForObject(consumerBaseUrl + "/api/records/" + id, ConsumerRecord.class);
    }
}
