package com.benchmark.lab_result_service.seed;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads the committed UTF-8 seed resources under {@code seed/} (contract §6.4). Pure Java, no Spring. */
final class SeedResources {

    private SeedResources() {}

    /** Non-blank lines that do not start with {@code #}, trimmed, in file order. */
    static List<String> dataLines(String classpathResource) {
        try (InputStream in = SeedResources.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("Missing seed resource " + classpathResource);
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    String trimmed = line.strip();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        lines.add(trimmed);
                    }
                }
            }
            return List.copyOf(lines);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read seed resource " + classpathResource, e);
        }
    }
}
