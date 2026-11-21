package com.benchmark.lab_result_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@code lab.runtime.jvm.inputArguments}: secret-looking system properties are reported without their value. */
class LabInfoContributorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "-Dspring.datasource.password=s3cret | -Dspring.datasource.password=******",
            "-DDB_PASSWORD=x=y                   | -DDB_PASSWORD=******",
            "-Dapi.token=abc                     | -Dapi.token=******",
            "-Dmy.Secret.key=abc                 | -Dmy.Secret.key=******",
            "-Daws.credentials=abc               | -Daws.credentials=******",
            "-Xmx512m                            | -Xmx512m",
            "-XX:ActiveProcessorCount=2          | -XX:ActiveProcessorCount=2",
            "-Dfile.encoding=UTF-8               | -Dfile.encoding=UTF-8",
            "-javaagent:/x/password.jar          | -javaagent:/x/password.jar"})
    void redactsSecretSystemPropertyValuesOnly(String argument, String expected) {
        assertThat(LabInfoContributor.redact(argument)).isEqualTo(expected);
    }
}
