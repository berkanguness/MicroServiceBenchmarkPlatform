package com.benchmark.lab_result_service.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Contract §6.4 / §10 P2: exactly 40 distinct names each, in the committed order, Turkish characters intact. */
class NameListsTest {

    @Test
    void bothListsHaveFortyDistinctNames() {
        assertThat(NameLists.FIRST_NAMES).hasSize(40).doesNotHaveDuplicates().allSatisfy(n -> assertThat(n).isNotBlank());
        assertThat(NameLists.LAST_NAMES).hasSize(40).doesNotHaveDuplicates().allSatisfy(n -> assertThat(n).isNotBlank());
    }

    @Test
    void orderAndUtf8CharactersAreAsCommitted() {
        assertThat(NameLists.FIRST_NAMES).startsWith("Ahmet", "Ayşe", "Mehmet").endsWith("Gül", "Serkan", "Çağla");
        assertThat(NameLists.FIRST_NAMES.get(12)).isEqualTo("İbrahim");     // capital dotted I
        assertThat(NameLists.FIRST_NAMES.get(15)).isEqualTo("Şerife");      // Ş
        assertThat(NameLists.LAST_NAMES).startsWith("Yılmaz", "Kaya", "Demir").endsWith("Turan", "Erdem", "Sezer");
        assertThat(NameLists.LAST_NAMES.get(0)).isEqualTo("Yılmaz");          // dotless ı
        assertThat(NameLists.LAST_NAMES.get(36)).isEqualTo("Ünal");          // Ü
    }
}
