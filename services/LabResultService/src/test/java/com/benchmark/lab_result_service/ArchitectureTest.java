package com.benchmark.lab_result_service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;

@AnalyzeClasses(packages = "com.benchmark.lab_result_service", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // Rule 1: adapters see only core.api (plus their own framework and, for gRPC, com.benchmark.proto)
    @ArchTest
    static final ArchRule adaptersUseOnlyCoreApi = noClasses()
            .that().resideInAPackage("..adapter..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..core.service..", "..persistence..", "..seed..")
            .allowEmptyShould(true);                       // P1a: no adapter classes exist yet

    // Rule 2: core is protocol-free, and core.api is pure (JDK + jspecify)
    @ArchTest
    static final ArchRule coreIsProtocolFree = CompositeArchRule
            .of(noClasses().that().resideInAPackage("..core..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..adapter..", "org.springframework.web..", "org.springframework.graphql..",
                            "io.grpc..", "com.benchmark.proto..", "tools.jackson..", "com.fasterxml.jackson.."))
            .and(classes().that().resideInAPackage("..core.api..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "org.jspecify..", "..core.api.."));

    // Rule 3: no adapter depends on another adapter (covers B's adapter.grpc too)
    @ArchTest
    static final ArchRule adaptersAreIndependent = slices()
            .matching("com.benchmark.lab_result_service.adapter.(*)..")
            .should().notDependOnEachOther()
            .allowEmptyShould(true);                       // P1a: no adapter slices exist yet
}
