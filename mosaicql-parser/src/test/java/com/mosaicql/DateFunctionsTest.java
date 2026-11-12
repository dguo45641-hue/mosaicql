package com.mosaicql;

import com.mosaicql.runtime.FunctionRegistry;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DateFunctionsTest {
    private final FunctionRegistry functions = new FunctionRegistry();

    @Test void extractsAndCalculatesDateParts() {
        var date = LocalDate.of(2026, 9, 17);
        assertEquals("2026", functions.apply("year", List.of(date)).toString());
        assertEquals("9", functions.apply("month", List.of(date)).toString());
        assertEquals("17", functions.apply("day", List.of(date)).toString());
        assertEquals(LocalDate.of(2026, 9, 20), functions.apply("date_add", List.of(date, 3)));
        assertEquals("3", functions.apply("date_diff", List.of(LocalDate.of(2026, 9, 20), date)).toString());
    }

    @Test void formatsDateWithJavaDatePattern() {
        assertEquals("2026/09/17", functions.apply("format_date", List.of(
            LocalDate.of(2026, 9, 17), "yyyy/MM/dd")));
    }
}
