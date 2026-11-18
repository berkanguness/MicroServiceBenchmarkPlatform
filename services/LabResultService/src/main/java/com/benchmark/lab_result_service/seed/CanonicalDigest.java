package com.benchmark.lab_result_service.seed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * SHA-256 over the canonical text form of the dataset (contract §6.5): UTF-8, table-major (all patients, then all
 * reports, then all items), id ascending, one {@code |}-separated line per row terminated by {@code \n}:
 * <pre>
 * P|&lt;id&gt;|&lt;tcNo&gt;|&lt;fullName&gt;|&lt;birthDate yyyy-MM-dd&gt;|&lt;bloodType&gt;
 * R|&lt;id&gt;|&lt;patientId&gt;|&lt;doctorName&gt;|&lt;sampleType&gt;|&lt;createdAt Instant.toString()&gt;
 * I|&lt;id&gt;|&lt;reportId&gt;|&lt;testCode&gt;|&lt;testName&gt;|&lt;Double.toString(resultValue)&gt;|&lt;unit&gt;|&lt;true|false&gt;
 * </pre>
 * The generator feeds its in-memory rows, the {@code full} verifier feeds rows read from the database; both must
 * produce the same 64 lowercase hex characters. The order is enforced: a row out of table-major / ascending-id
 * order throws, so the two producers cannot silently disagree on ordering.
 */
public final class CanonicalDigest {

    private final MessageDigest sha256;
    private final StringBuilder line = new StringBuilder(160);
    private int table;          // 0 = patients, 1 = reports, 2 = items
    private long lastId = Long.MIN_VALUE;
    private boolean finished;

    public CanonicalDigest() {
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every Java platform", e);
        }
    }

    public void patient(long id, String tcNo, String fullName, LocalDate birthDate, String bloodType) {
        advance(0, id);
        line.append("P|").append(id).append('|').append(tcNo).append('|').append(fullName).append('|')
                .append(birthDate).append('|').append(bloodType);
        flush();
    }

    public void report(long id, long patientId, String doctorName, String sampleType, Instant createdAt) {
        advance(1, id);
        line.append("R|").append(id).append('|').append(patientId).append('|').append(doctorName).append('|')
                .append(sampleType).append('|').append(createdAt.toString());
        flush();
    }

    public void item(long id, long reportId, String testCode, String testName, double resultValue, String unit,
                     boolean critical) {
        advance(2, id);
        line.append("I|").append(id).append('|').append(reportId).append('|').append(testCode).append('|')
                .append(testName).append('|').append(Double.toString(resultValue)).append('|').append(unit)
                .append('|').append(critical);
        flush();
    }

    /** Finishes the digest and returns 64 lowercase hex characters. The instance cannot be reused. */
    public String hex() {
        if (finished) {
            throw new IllegalStateException("digest already finished");
        }
        finished = true;
        return HexFormat.of().formatHex(sha256.digest());
    }

    private void advance(int rowTable, long id) {
        if (finished) {
            throw new IllegalStateException("digest already finished");
        }
        if (rowTable < table) {
            throw new IllegalStateException("rows must be table-major (patients, reports, items)");
        }
        if (rowTable > table) {
            table = rowTable;
            lastId = Long.MIN_VALUE;
        }
        if (id <= lastId) {
            throw new IllegalStateException("ids must be strictly ascending within a table: " + id + " after " + lastId);
        }
        lastId = id;
    }

    private void flush() {
        line.append('\n');
        sha256.update(line.toString().getBytes(StandardCharsets.UTF_8));
        line.setLength(0);
    }
}
