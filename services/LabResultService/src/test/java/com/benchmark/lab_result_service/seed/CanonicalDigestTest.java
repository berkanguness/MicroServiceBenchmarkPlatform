package com.benchmark.lab_result_service.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Contract §6.5: exact line format, UTF-8, table-major, ascending ids. */
class CanonicalDigestTest {

    @Test
    void hashesTheExactCanonicalText() throws Exception {
        CanonicalDigest d = new CanonicalDigest();
        d.patient(1, "12345678906", "Ayşe Yılmaz", LocalDate.of(1980, 2, 3), "AB+");
        d.report(1, 1, "Dr. Ömer Şen", "BLOOD", Instant.parse("2025-12-31T23:59:59Z"));
        d.item(1, 1, "TSH", "Thyroid-stimulating hormone", 0.0, "mIU/L", true);
        d.item(2, 1, "GLU", "Glucose", 85.5, "mg/dL", false);

        String text = """
                P|1|12345678906|Ayşe Yılmaz|1980-02-03|AB+
                R|1|1|Dr. Ömer Şen|BLOOD|2025-12-31T23:59:59Z
                I|1|1|TSH|Thyroid-stimulating hormone|0.0|mIU/L|true
                I|2|1|GLU|Glucose|85.5|mg/dL|false
                """;
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        assertThat(d.hex()).isEqualTo(expected).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsRowsOutOfOrder() {
        CanonicalDigest d = new CanonicalDigest();
        d.report(1, 1, "Dr. X", "BLOOD", Instant.EPOCH);
        assertThatThrownBy(() -> d.patient(2, "1", "X", LocalDate.EPOCH, "A+")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> d.report(1, 1, "Dr. X", "BLOOD", Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class);
    }
}
