package com.benchmark.lab_result_service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Contract §2.2: {@link ArchitectureTest}'s adapter rules tolerate empty packages ({@code allowEmptyShould}), so this
 * plain JUnit test (not a fourth ArchUnit rule) asserts that the same production import does contain REST and GraphQL
 * adapter classes; a mis-scoped import can then not pass the rules vacuously.
 */
class AdapterPresenceTest {

    /** The same import as {@code @AnalyzeClasses} in {@link ArchitectureTest}. */
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.benchmark.lab_result_service");

    @Test
    void theProductionImportContainsRestAndGraphQlAdapters() {
        assertThat(PRODUCTION.stream().map(JavaClass::getPackageName))
                .as("..adapter.rest..").anyMatch(p -> p.contains(".adapter.rest"))
                .as("..adapter.graphql..").anyMatch(p -> p.contains(".adapter.graphql"));
        assertThat(PRODUCTION.stream().map(JavaClass::getName))
                .contains("com.benchmark.lab_result_service.adapter.rest.PatientRestController",
                        "com.benchmark.lab_result_service.adapter.graphql.LabGraphQlController")
                .noneMatch(name -> name.endsWith("Test") || name.endsWith("IT"));
    }
}
