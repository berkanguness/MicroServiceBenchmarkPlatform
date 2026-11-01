-- p1-fixture.sql — test-only. Same shape as §6.1 (1000 / 10080 / 100800), simple deterministic values.
-- tc_no: 10 digits b = 1000000000 + p, then d11 = (digit sum of b + 1) mod 10.
-- The official rule needs d11 = (digit sum of d1..d10) mod 10, so every value is deliberately checksum-invalid (D4).
INSERT INTO patient (id, tc_no, full_name, birth_date, blood_type)
SELECT p,
       t.b || (((SELECT sum(substr(t.b, i, 1)::int) FROM generate_series(1, 10) AS i) + 1) % 10)::text,
       'Test Patient ' || p, DATE '1980-01-01' + (p % 9000),
       (ARRAY['A+','A-','B+','B-','AB+','AB-','O+','O-'])[1 + p % 8]
FROM generate_series(1, 1000) AS p
CROSS JOIN LATERAL (SELECT (1000000000 + p)::text AS b) AS t;

-- reports: patient 1 → ids 1..100; patient 2 → none; patient p >= 3 → 101 + (p-3)*10 + k   (k = 0 latest)
INSERT INTO lab_report (id, patient_id, doctor_name, sample_type, created_at)
SELECT CASE WHEN p = 1 THEN 1 + k ELSE 101 + (p - 3) * 10 + k END, p, 'Dr. Test ' || p,
       (ARRAY['BLOOD','SERUM','PLASMA','URINE','SALIVA'])[1 + k % 5],
       TIMESTAMPTZ '2026-01-01 00:00:00+00' - make_interval(days => 3 * k)
FROM generate_series(1, 1000) AS p
CROSS JOIN LATERAL generate_series(0, CASE WHEN p = 1 THEN 99 WHEN p = 2 THEN -1 ELSE 9 END) AS k;

-- items: 10 per report, item id = (report_id - 1) * 10 + j, test codes T01..T10
INSERT INTO lab_test_item (id, report_id, test_code, test_name, result_value, unit, is_critical)
SELECT (r.id - 1) * 10 + j, r.id, 'T' || lpad(j::text, 2, '0'), 'Test ' || j, j + 0.5, 'u', (j = 10)
FROM lab_report r CROSS JOIN generate_series(1, 10) AS j;

SELECT setval(pg_get_serial_sequence('patient', 'id'),       (SELECT max(id) FROM patient));
SELECT setval(pg_get_serial_sequence('lab_report', 'id'),    (SELECT max(id) FROM lab_report));
SELECT setval(pg_get_serial_sequence('lab_test_item', 'id'), (SELECT max(id) FROM lab_test_item));
ANALYZE patient, lab_report, lab_test_item;
