package com.ccdev.drainage.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationNormalizerTest {

    private static BigDecimal v(String s) {
        return new BigDecimal(s);
    }

    @Test
    void forwardKeepsStation() {
        BigDecimal[] r = StationNormalizer.normalizeInterval(v("10.000"), v("12.500"),
                v("60.000"), "FORWARD");
        assertEquals(v("10.000"), r[0]);
        assertEquals(v("12.500"), r[1]);
    }

    @Test
    void reversedFlipsAroundDownstreamOrigin() {
        // 反向里程：管长 60，原始 [47.5,50]（0 在下游井）⇒ 归一 [10,12.5]，端点交换
        BigDecimal[] r = StationNormalizer.normalizeInterval(v("47.500"), v("50.000"),
                v("60.000"), "REVERSED");
        assertEquals(v("10.000"), r[0]);
        assertEquals(v("12.500"), r[1]);
    }

    @Test
    void reversedSinglePointAtDownstreamBecomesLength() {
        BigDecimal p = StationNormalizer.normalizePoint(v("0.000"), v("40.000"), "REVERSED");
        assertEquals(v("40.000"), p);
    }

    @Test
    void containmentAndOverlap() {
        assertTrue(StationNormalizer.contained(v("0.000"), v("40.000"), v("40.000")));
        assertFalse(StationNormalizer.contained(v("21.000"), v("41.000"), v("40.000")));
        assertTrue(StationNormalizer.overlaps(v("21.000"), v("23.000"), v("22.000"), v("25.000")));
        assertFalse(StationNormalizer.overlaps(v("0.000"), v("10.000"), v("22.000"), v("40.000")));
    }
}
