package com.ccdev.drainage.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 里程归一：把承包商视频里程统一换算为“沿管段走向、上游井为 0”的归一里程。
 * FORWARD ：视频里程与归一里程同向，norm = station
 * REVERSED：反向里程（0 在下游井），norm = length - station，区间端点随之交换
 */
public final class StationNormalizer {

    private StationNormalizer() {
    }

    public static BigDecimal normalizePoint(BigDecimal station, BigDecimal segmentLength, String direction) {
        if ("REVERSED".equals(direction)) {
            return segmentLength.subtract(station).setScale(3, RoundingMode.HALF_UP);
        }
        return station.setScale(3, RoundingMode.HALF_UP);
    }

    /** 返回 [起点, 终点]，保证 start <= end。 */
    public static BigDecimal[] normalizeInterval(BigDecimal rawStart, BigDecimal rawEnd,
                                                 BigDecimal segmentLength, String direction) {
        if ("REVERSED".equals(direction)) {
            BigDecimal normStart = segmentLength.subtract(rawEnd).setScale(3, RoundingMode.HALF_UP);
            BigDecimal normEnd = segmentLength.subtract(rawStart).setScale(3, RoundingMode.HALF_UP);
            return new BigDecimal[]{normStart, normEnd};
        }
        return new BigDecimal[]{rawStart.setScale(3, RoundingMode.HALF_UP),
                rawEnd.setScale(3, RoundingMode.HALF_UP)};
    }

    /** 区间是否完整落在管段 [0, length] 内（含端点容差 0.001m）。 */
    public static boolean contained(BigDecimal start, BigDecimal end, BigDecimal segmentLength) {
        BigDecimal eps = new BigDecimal("0.001");
        return start.compareTo(eps.negate()) >= 0 && end.compareTo(segmentLength.add(eps)) <= 0;
    }

    /** 两个闭区间是否重叠（端点相接也算同位置，容差 0.5m 的近邻判定在聚类处处理）。 */
    public static boolean overlaps(BigDecimal aStart, BigDecimal aEnd, BigDecimal bStart, BigDecimal bEnd) {
        return aStart.compareTo(bEnd) <= 0 && bStart.compareTo(aEnd) <= 0;
    }
}
