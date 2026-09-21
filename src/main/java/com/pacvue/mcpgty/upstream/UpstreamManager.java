package com.pacvue.mcpgty.upstream;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class UpstreamManager {

    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreaker getOrCreate(String alias) {
        return breakers.computeIfAbsent(alias, CircuitBreaker::new);
    }

    public CircuitBreaker get(String alias) {
        return breakers.get(alias);
    }

    public Map<String, CircuitBreaker> all() {
        return breakers;
    }
}
