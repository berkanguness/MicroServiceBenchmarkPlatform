package com.benchmark.lab_result_service.seed;

/**
 * Synthetic T.C. Kimlik-shaped numbers (contract §6.3, D4). The generator draws ten digits {@code d1..d10}
 * ({@code d1} in 1..9); the eleventh is {@code (d1 + … + d10 + 1) mod 10}. The official rule requires
 * {@code d11 = (d1 + … + d10) mod 10}, so every value is deliberately invalid as a real identity, while still
 * matching {@code ^[1-9][0-9]{10}$}.
 */
public final class TcNo {

    private TcNo() {}

    /** Builds the 11-digit value from the ten drawn digits. */
    public static String of(int[] tenDigits) {
        if (tenDigits.length != 10) {
            throw new IllegalArgumentException("expected 10 digits, got " + tenDigits.length);
        }
        if (tenDigits[0] < 1 || tenDigits[0] > 9) {
            throw new IllegalArgumentException("first digit must be 1..9, got " + tenDigits[0]);
        }
        StringBuilder sb = new StringBuilder(11);
        int sum = 0;
        for (int d : tenDigits) {
            if (d < 0 || d > 9) {
                throw new IllegalArgumentException("digit out of range: " + d);
            }
            sum += d;
            sb.append((char) ('0' + d));
        }
        sb.append((char) ('0' + (sum + 1) % 10));
        return sb.toString();
    }
}
