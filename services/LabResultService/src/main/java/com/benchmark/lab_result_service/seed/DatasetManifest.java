package com.benchmark.lab_result_service.seed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dataset descriptor (contract §6.5). {@link #expected()} reads the committed main resource
 * {@code seed/dataset-v1.json}, the <em>only</em> copy of the expected checksum for generator version 1. Both the
 * runtime {@link DatasetVerifier} and {@code SyntheticDatasetGeneratorTest} compare against it. The same shape is
 * stored in the {@code dataset_meta} row the seeder writes last.
 */
public record DatasetManifest(String name, int generatorVersion, long seed, int patientCount, int reportCount,
                              int itemCount, String checksumSha256) {

    public static final String RESOURCE = "/seed/dataset-v1.json";

    public DatasetManifest {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(checksumSha256, "checksumSha256");
    }

    private static final DatasetManifest EXPECTED = load();

    /** The committed descriptor of generator version 1. */
    public static DatasetManifest expected() {
        return EXPECTED;
    }

    /** The manifest describing a generated dataset (what the seeder writes to {@code dataset_meta}). */
    public static DatasetManifest of(SyntheticDatasetGenerator.Dataset dataset) {
        return new DatasetManifest(SyntheticDatasetGenerator.DATASET_NAME, SyntheticDatasetGenerator.GENERATOR_VERSION,
                SyntheticDatasetGenerator.SEED, dataset.patients().size(), dataset.reports().size(),
                dataset.items().size(), dataset.checksumSha256());
    }

    private static DatasetManifest load() {
        try (InputStream in = DatasetManifest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing dataset descriptor " + RESOURCE);
            }
            DatasetManifest manifest = JsonMapper.builder().build().readValue(in, DatasetManifest.class);
            if (!manifest.checksumSha256().matches("[0-9a-f]{64}")) {
                throw new IllegalStateException(RESOURCE + ": checksumSha256 is not 64 lowercase hex characters");
            }
            return manifest;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read dataset descriptor " + RESOURCE, e);
        }
    }
}
