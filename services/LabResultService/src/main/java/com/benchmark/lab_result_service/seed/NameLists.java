package com.benchmark.lab_result_service.seed;

import java.util.List;

/**
 * {@code FIRST_NAMES} and {@code LAST_NAMES} of generator version 1 (contract §6.4): exactly 40 each, in the committed
 * order, UTF-8 with the Turkish characters intact. The list index is what the generator draws, so the order is part
 * of the dataset definition.
 */
public final class NameLists {

    public static final String FIRST_NAMES_RESOURCE = "/seed/first-names.txt";
    public static final String LAST_NAMES_RESOURCE = "/seed/last-names.txt";

    /** Size of each list; the generator draws {@code nextInt(SIZE)} (§6.3). */
    public static final int SIZE = 40;

    public static final List<String> FIRST_NAMES = load(FIRST_NAMES_RESOURCE);
    public static final List<String> LAST_NAMES = load(LAST_NAMES_RESOURCE);

    private NameLists() {}

    private static List<String> load(String resource) {
        List<String> names = SeedResources.dataLines(resource);
        if (names.size() != SIZE) {
            throw new IllegalStateException(resource + " must have " + SIZE + " names, has " + names.size());
        }
        return names;
    }
}
