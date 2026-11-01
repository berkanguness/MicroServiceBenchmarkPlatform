package com.benchmark.lab_result_service.testsupport;

/**
 * Independent implementation of the official T.C. Kimlik No check (test-only, contract §10 P1/P2). A number is
 * valid only if it has 11 digits, the first digit is not 0, and both check digits hold:
 * <ul>
 *   <li>d10 = ((d1 + d3 + d5 + d7 + d9) * 7 − (d2 + d4 + d6 + d8)) mod 10</li>
 *   <li>d11 = (d1 + d2 + … + d10) mod 10</li>
 * </ul>
 * Fixture and generator values must all be <em>invalid</em> under this rule (D4).
 */
public final class OfficialTcKimlikValidator {

    private OfficialTcKimlikValidator() {}

    public static boolean isValid(String tcNo) {
        if (tcNo == null || tcNo.length() != 11) {
            return false;
        }
        int[] d = new int[11];
        for (int i = 0; i < 11; i++) {
            char c = tcNo.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            d[i] = c - '0';
        }
        if (d[0] == 0) {
            return false;
        }
        int odd = d[0] + d[2] + d[4] + d[6] + d[8];
        int even = d[1] + d[3] + d[5] + d[7];
        if (Math.floorMod(odd * 7 - even, 10) != d[9]) {
            return false;
        }
        int firstTen = 0;
        for (int i = 0; i < 10; i++) {
            firstTen += d[i];
        }
        return firstTen % 10 == d[10];
    }
}
