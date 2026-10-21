package br.pucminas.cd.waste.controller;

import br.pucminas.cd.waste.clock.LamportClock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final LamportClock clock;

    public HealthController(LamportClock clock) {
        this.clock = clock;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "service", "coordinator",
                "status", "UP",
                "logicalTime", clock.get(),
                "timestamp", Instant.now().toString()
        );
    }
}
