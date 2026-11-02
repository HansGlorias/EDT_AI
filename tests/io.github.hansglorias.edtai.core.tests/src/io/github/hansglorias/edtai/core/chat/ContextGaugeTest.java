package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ContextGaugeTest {

    @Test
    void levelAndTextFollowShareOfWindow() {
        ContextGauge low = new ContextGauge(45_000, 128_000, true);
        assertEquals(35, low.percent());
        assertEquals(ContextGauge.Level.LOW, low.level());
        assertEquals("Контекст ≈45k из 128k (35%)", low.describe());

        assertEquals(ContextGauge.Level.MEDIUM, new ContextGauge(64_000, 128_000, false).level(), "половина — жёлтый");
        assertEquals(ContextGauge.Level.HIGH, new ContextGauge(103_000, 128_000, false).level(), "80% и больше — красный");
        ContextGauge over = new ContextGauge(200_000, 128_000, false);
        assertEquals(1.0, over.ratio(), "больше окна — полное кольцо");
        assertEquals("Контекст 200k из 128k (100%)", over.describe());
        assertEquals(0, new ContextGauge(-5, 1000, true).used());
        assertThrows(IllegalArgumentException.class, () -> new ContextGauge(1, 0, true));
    }
}
