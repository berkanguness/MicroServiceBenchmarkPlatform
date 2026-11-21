package com.benchmark.lab_result_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code lab.runtime.jvm.inputArguments} is an allowlist: heap/stack sizes and {@code -XX:} switches or numbers are
 * kept in their order; every other argument (system properties, agents, module options, {@code -XX:} options with a
 * path or text value) is omitted, not redacted in place. Synthetic inputs only.
 */
class LabInfoContributorTest {

    @Test
    void keepsOnlyAllowlistedArgumentsInTheirOrder() {
        List<String> arguments = List.of(
                "-Dspring.datasource.password=x",
                "-Xmx512m",
                "-Dfoo=bar",
                "-XX:+UseG1GC",
                "-javaagent:/x.jar=opt",
                "-Xms512m",
                "-agentlib:jdwp=transport=dt_socket,server=y,address=5005",
                "--add-opens",
                "java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "-XX:MaxRAMPercentage=75.0",
                "-Dspring.datasource.url=jdbc:postgresql://db:5432/lab?password=x",
                "-XX:-UseCompressedOops",
                "-XX:HeapDumpPath=/secret/dumps",
                "-Xss1m",
                "-XX:ActiveProcessorCount=2",
                "-XX:MaxDirectMemorySize=256M");

        assertThat(LabInfoContributor.allowedInputArguments(arguments)).containsExactly(
                "-Xmx512m", "-XX:+UseG1GC", "-Xms512m", "-XX:MaxRAMPercentage=75.0", "-XX:-UseCompressedOops",
                "-Xss1m", "-XX:ActiveProcessorCount=2", "-XX:MaxDirectMemorySize=256M");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-Dspring.datasource.password=x", "-Dfoo=bar", "-DDB_PASSWORD=x=y", "-Dfile.encoding=UTF-8",
            "-Dspring.datasource.url=jdbc:postgresql://db/lab?password=x",
            "-javaagent:/x.jar=opt", "-javaagent:/x/password.jar", "-agentlib:jdwp=transport=dt_socket",
            "-agentpath:/opt/agent.so=key", "--add-opens", "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "java.base/java.lang=ALL-UNNAMED", "-XX:HeapDumpPath=/tmp/dumps", "-XX:ErrorFile=/tmp/hs_err.log",
            "-XX:OnOutOfMemoryError=kill -9 %p", "-XX:StartFlightRecording=filename=/tmp/r.jfr",
            "-XX:Flags=/etc/flags", "-XX:VMOptionsFile=/etc/opts", "-Xmx512m-secret", "-Xmx", "-Xlog:gc:file=/tmp/gc",
            "-verbose:gc", "-ea", "-cp", "/app/lib/*", "-Duser.dir=/home/someone"})
    void omitsEverythingElse(String argument) {
        assertThat(LabInfoContributor.allowedInputArguments(List.of(argument))).isEmpty();
    }
}
