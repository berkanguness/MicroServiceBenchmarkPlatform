package com.benchmark.lab_result_service.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Controls for the validator itself, so "every fixture value is invalid" cannot pass with a broken validator. */
class OfficialTcKimlikValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"10000000146", "10000000078", "10000000214"})
    void acceptsOfficiallyValidNumbers(String tcNo) {
        assertThat(OfficialTcKimlikValidator.isValid(tcNo)).isTrue();
    }

    @Test
    void rejectsWrongTenthDigit() {
        assertThat(OfficialTcKimlikValidator.isValid("10000000156")).isFalse();
    }

    @Test
    void rejectsWrongEleventhDigit() {
        assertThat(OfficialTcKimlikValidator.isValid("10000000147")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1000000014", "100000001460", "01000000146", "1000000014a"})
    void rejectsMalformedInput(String tcNo) {
        assertThat(OfficialTcKimlikValidator.isValid(tcNo)).isFalse();
    }

    @Test
    void p1FixtureFormulaExampleIsInvalid() {
        // contract §6.8: patient 1 -> 10000000013
        assertThat(OfficialTcKimlikValidator.isValid("10000000013")).isFalse();
    }
}
