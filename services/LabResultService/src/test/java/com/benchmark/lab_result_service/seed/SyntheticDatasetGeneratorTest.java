package com.benchmark.lab_result_service.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator.Dataset;
import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator.ItemRecord;
import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator.PatientRecord;
import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator.ReportRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Generator version 1 against its committed descriptor (contract §6.1–§6.5, §10 P2). The expected checksum is read
 * from {@code seed/dataset-v1.json} through {@link DatasetManifest#expected()}; there is no second copy here.
 */
class SyntheticDatasetGeneratorTest {

    private static Dataset dataset;

    @BeforeAll
    static void generate() {
        dataset = SyntheticDatasetGenerator.generate();
    }

    @Test
    void outputEqualsTheCommittedDescriptor() {
        DatasetManifest expected = DatasetManifest.expected();
        assertThat(DatasetManifest.of(dataset)).isEqualTo(expected);
        assertThat(expected.name()).isEqualTo(SyntheticDatasetGenerator.DATASET_NAME).isEqualTo("ym491");
        assertThat(expected.generatorVersion()).isEqualTo(SyntheticDatasetGenerator.GENERATOR_VERSION).isEqualTo(1);
        assertThat(expected.seed()).isEqualTo(SyntheticDatasetGenerator.SEED).isEqualTo(4912026L);
    }

    @Test
    void twoRunsAreIdentical() {
        Dataset again = SyntheticDatasetGenerator.generate();
        assertThat(again.checksumSha256()).isEqualTo(dataset.checksumSha256());
        assertThat(again.patients()).isEqualTo(dataset.patients());
        assertThat(again.reports()).isEqualTo(dataset.reports());
        assertThat(again.items()).isEqualTo(dataset.items());
    }

    @Test
    void volumesAreExact() {
        assertThat(dataset.patients()).hasSize(1_000);
        assertThat(dataset.reports()).hasSize(10_080);
        assertThat(dataset.items()).hasSize(100_800);
    }

    @Test
    void idsAreSequentialInGenerationOrder() {
        assertThat(dataset.patients()).extracting(PatientRecord::id)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 1_000).boxed().toList());
        assertThat(dataset.reports()).extracting(ReportRecord::id)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 10_080).boxed().toList());
        assertThat(dataset.items()).extracting(ItemRecord::id)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 100_800).boxed().toList());
    }

    @Test
    void reportIdsPerPatientFollowSection62() {
        Map<Long, List<ReportRecord>> byPatient = reportsByPatient();
        assertThat(byPatient.get(1L)).extracting(ReportRecord::id)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 100).boxed().toList());
        assertThat(byPatient.get(2L)).isNull();
        for (long p = 3; p <= 1_000; p++) {
            long first = 101 + (p - 3) * 10;
            assertThat(byPatient.get(p)).extracting(ReportRecord::id)
                    .containsExactlyElementsOf(LongStream.range(first, first + 10).boxed().toList());
        }
    }

    @Test
    void scenarioPatientsHaveTheirShape() {
        Map<Long, List<ReportRecord>> byPatient = reportsByPatient();
        Map<Long, List<ItemRecord>> byReport = itemsByReport();

        // patient 1: 100 reports x 10 items, items 1..1000
        assertThat(byPatient.get(1L)).hasSize(100).allSatisfy(r -> assertThat(byReport.get(r.id())).hasSize(10));
        assertThat(byPatient.get(1L).stream().flatMap(r -> byReport.get(r.id()).stream()).map(ItemRecord::id))
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 1_000).boxed().toList());
        // patient 2: empty history
        assertThat(byPatient).doesNotContainKey(2L);
        // patient 3: 10 x 10, latest = report 101 with items 1001..1010; its items are 1001..1100
        List<ReportRecord> p3 = byPatient.get(3L);
        assertThat(p3).hasSize(10).allSatisfy(r -> assertThat(byReport.get(r.id())).hasSize(10));
        ReportRecord latest = p3.stream().max((a, b) -> a.createdAt().compareTo(b.createdAt())).orElseThrow();
        assertThat(latest.id()).isEqualTo(101L);
        assertThat(byReport.get(101L)).extracting(ItemRecord::id)
                .containsExactlyElementsOf(LongStream.rangeClosed(1_001, 1_010).boxed().toList());
        assertThat(p3.stream().flatMap(r -> byReport.get(r.id()).stream()).map(ItemRecord::id))
                .containsExactlyElementsOf(LongStream.rangeClosed(1_001, 1_100).boxed().toList());
        // every background patient has 10 x 10
        for (long p = 4; p <= 1_000; p++) {
            assertThat(byPatient.get(p)).hasSize(10);
        }
        assertThat(byReport.values()).allSatisfy(items -> assertThat(items).hasSize(10));
    }

    @Test
    void createdAtIsWholeSecondsAndStrictlyDecreasingPerPatient() {
        for (List<ReportRecord> reports : reportsByPatient().values()) {
            for (int k = 0; k < reports.size(); k++) {
                Instant t = reports.get(k).createdAt();
                assertThat(t.getNano()).isZero();
                assertThat(t).isBeforeOrEqualTo(SyntheticDatasetGenerator.BASE);
                if (k > 0) {
                    assertThat(t).as("report k=%d strictly older than k-1", k).isBefore(reports.get(k - 1).createdAt());
                }
            }
        }
    }

    @Test
    void itemsAreConsistentWithTheCatalogue() {
        Map<String, TestCatalogue.Entry> catalogue = TestCatalogue.entries().stream()
                .collect(Collectors.toMap(TestCatalogue.Entry::code, e -> e));
        int criticals = 0;
        for (ItemRecord i : dataset.items()) {
            TestCatalogue.Entry c = catalogue.get(i.testCode());
            assertThat(c).as("item %d code", i.id()).isNotNull();
            assertThat(i.testName()).isEqualTo(c.name());
            assertThat(i.unit()).isEqualTo(c.unit());
            assertThat(i.critical()).as("item %d isCritical", i.id())
                    .isEqualTo(i.resultValue() < c.criticalLow() || i.resultValue() > c.criticalHigh());
            assertThat(Double.isFinite(i.resultValue())).isTrue();
            assertThat(Math.round(i.resultValue() * 10) / 10.0).as("one decimal").isEqualTo(i.resultValue());
            if (i.critical()) {
                criticals++;
            }
        }
        // ~5 % critical draws; rounding back into range can only lower it
        assertThat(criticals).isBetween(3_000, 7_000);
    }

    @Test
    void itemCodesAreUniquePerReportAndInCatalogueOrder() {
        Map<String, Integer> index = new HashMap<>();
        List<TestCatalogue.Entry> entries = TestCatalogue.entries();
        for (int i = 0; i < entries.size(); i++) {
            index.put(entries.get(i).code(), i);
        }
        for (List<ItemRecord> items : itemsByReport().values()) {
            Set<String> codes = new HashSet<>();
            int previous = -1;
            for (ItemRecord i : items) {
                assertThat(codes.add(i.testCode())).as("duplicate %s in report %d", i.testCode(), i.reportId()).isTrue();
                assertThat(index.get(i.testCode())).isGreaterThan(previous);
                previous = index.get(i.testCode());
            }
        }
    }

    @Test
    void patientFieldsAreInTheirDomains() {
        assertThat(dataset.patients()).allSatisfy(p -> {
            assertThat(p.birthDate()).isBetween(LocalDate.of(1940, 1, 1), LocalDate.of(2004, 12, 31));
            assertThat(SyntheticDatasetGenerator.BLOOD_TYPES).contains(p.bloodType());
            assertThat(p.fullName()).hasSizeLessThanOrEqualTo(100).isNotBlank();
        });
        assertThat(dataset.reports()).allSatisfy(r -> {
            assertThat(SyntheticDatasetGenerator.SAMPLE_TYPES).contains(r.sampleType());
            assertThat(r.doctorName()).startsWith("Dr. ").hasSizeLessThanOrEqualTo(100);
        });
    }

    private static Map<Long, List<ReportRecord>> reportsByPatient() {
        return dataset.reports().stream().collect(Collectors.groupingBy(ReportRecord::patientId));
    }

    private static Map<Long, List<ItemRecord>> itemsByReport() {
        return dataset.items().stream().collect(Collectors.groupingBy(ItemRecord::reportId));
    }
}
