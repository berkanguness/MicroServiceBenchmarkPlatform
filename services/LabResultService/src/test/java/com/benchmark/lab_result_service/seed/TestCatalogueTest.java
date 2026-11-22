package com.benchmark.lab_result_service.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract §6.4 / §10 P2: 20 entries in the committed order; critical bounds lie outside the normal range. */
class TestCatalogueTest {

    @Test
    void hasTwentyEntriesWithUniqueCodesInOrder() {
        List<TestCatalogue.Entry> entries = TestCatalogue.entries();
        assertThat(entries).hasSize(20);
        assertThat(entries).extracting(TestCatalogue.Entry::code).doesNotHaveDuplicates().containsExactly(
                "GLU", "HGB", "WBC", "PLT", "NA", "K", "CL", "CA", "CREA", "BUN",
                "ALT", "AST", "ALP", "TBIL", "ALB", "CHOL", "TRIG", "CRP", "TSH", "HBA1C");
    }

    @Test
    void criticalBoundsLieOutsideTheNormalRange() {
        assertThat(TestCatalogue.entries()).allSatisfy(e -> {
            assertThat(e.criticalLow()).as("%s criticalLow < normalMin", e.code()).isLessThan(e.normalMin());
            assertThat(e.normalMin()).as("%s normalMin < normalMax", e.code()).isLessThan(e.normalMax());
            assertThat(e.normalMax()).as("%s normalMax < criticalHigh", e.code()).isLessThan(e.criticalHigh());
            assertThat(e.criticalLow()).isGreaterThanOrEqualTo(0.0);
            assertThat(e.code().length()).isLessThanOrEqualTo(20);
            assertThat(e.unit().length()).isLessThanOrEqualTo(20);
        });
    }

    @Test
    void spotChecksAgainstTheContractTable() {
        assertThat(TestCatalogue.entries().getFirst())
                .isEqualTo(new TestCatalogue.Entry("GLU", "Glucose", "mg/dL", 70, 100, 40, 400));
        assertThat(TestCatalogue.entries().get(18))
                .isEqualTo(new TestCatalogue.Entry("TSH", "Thyroid-stimulating hormone", "mIU/L", 0.4, 4.0, 0.01, 20.0));
        assertThat(TestCatalogue.entries().get(19))
                .isEqualTo(new TestCatalogue.Entry("HBA1C", "Hemoglobin A1c", "%", 4.0, 5.6, 0, 14.0));
    }
}
