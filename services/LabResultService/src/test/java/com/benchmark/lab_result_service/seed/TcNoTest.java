package com.benchmark.lab_result_service.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator.PatientRecord;
import com.benchmark.lab_result_service.testsupport.OfficialTcKimlikValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract §6.3 / §10 P2, D4: generated tcNo values are well-formed, unique and officially invalid. */
class TcNoTest {

    @Test
    void everyGeneratedTcNoMatchesTheRegexIsUniqueAndFailsTheOfficialRule() {
        List<String> tcNos = SyntheticDatasetGenerator.generate().patients().stream().map(PatientRecord::tcNo).toList();
        assertThat(tcNos).hasSize(1_000).doesNotHaveDuplicates()
                .allSatisfy(tc -> {
                    assertThat(tc).matches("^[1-9][0-9]{10}$");
                    assertThat(OfficialTcKimlikValidator.isValid(tc)).as("officially valid: %s", tc).isFalse();
                });
    }

    @Test
    void checkDigitIsTheDigitSumPlusOne() {
        // 1+2+...+9+0 = 45 → (45 + 1) mod 10 = 6; the official rule would need 5
        assertThat(TcNo.of(new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 0})).isEqualTo("12345678906");
        assertThat(TcNo.of(new int[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9})).isEqualTo("99999999991");
    }

    @Test
    void theRuleIsAlwaysOfficiallyInvalidEvenWhenTheTenthDigitHolds() {
        // 10000000078 is officially valid (d10 and d11 both hold); our d11 differs by construction
        assertThat(OfficialTcKimlikValidator.isValid("10000000078")).isTrue();
        String ours = TcNo.of(new int[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 7});
        assertThat(ours).isEqualTo("10000000079");
        assertThat(OfficialTcKimlikValidator.isValid(ours)).isFalse();
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> TcNo.of(new int[9])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TcNo.of(new int[10])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TcNo.of(new int[] {1, 10, 0, 0, 0, 0, 0, 0, 0, 0}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
