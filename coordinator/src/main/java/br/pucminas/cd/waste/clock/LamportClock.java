package br.pucminas.cd.waste.clock;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class LamportClock {
    private final AtomicLong value = new AtomicLong(0);

    public long tick() {
        return value.incrementAndGet();
    }

    public long receive(long receivedValue) {
        return value.updateAndGet(current -> Math.max(current, receivedValue) + 1);
    }

    public long get() {
        return value.get();
    }
}
