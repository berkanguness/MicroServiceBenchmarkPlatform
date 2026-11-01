package com.benchmark.lab_result_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Startup-failure case (contract §10 P1), kept apart from runtime outages (P4 {@code RestErrorIT}): with the
 * datasource at a closed port the application context must fail to start, because Flyway and {@code validate}
 * cannot run. No HTTP assertion; readiness is never reached. No container is needed.
 */
class StartupDbDownIT {

    @Test
    void contextFailsToStartWhenTheDatabaseIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        SpringApplication app = new SpringApplicationBuilder(LabResultServiceApplication.class).build();

        long start = System.nanoTime();
        assertThatThrownBy(() -> app.run(
                        "--spring.profiles.active=test",
                        "--spring.datasource.url=jdbc:postgresql://127.0.0.1:" + closedPort + "/labdb",
                        "--spring.datasource.username=lab",
                        "--spring.datasource.password=lab",
                        "--server.port=0",
                        "--spring.grpc.server.port=0"))
                .satisfies(e -> assertThat(rootCause(e)).isInstanceOf(ConnectException.class));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).as("fails fast, does not hang").isLessThan(60_000);
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }
}
