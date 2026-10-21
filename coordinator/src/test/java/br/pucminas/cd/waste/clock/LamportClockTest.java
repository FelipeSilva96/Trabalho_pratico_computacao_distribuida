package br.pucminas.cd.waste.clock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LamportClockTest {
    @Test
    void tickAndReceiveFollowLamportRule() {
        LamportClock clock = new LamportClock();

        assertEquals(1, clock.tick());
        assertEquals(6, clock.receive(5));
        assertEquals(7, clock.tick());
        assertEquals(8, clock.receive(3));
    }
}
