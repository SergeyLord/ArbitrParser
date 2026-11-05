package dev.sergeylord.kad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HearingSplitTest {

    @Test
    void splitsDateTimeAndHall() {
        var hi = KadSearchClient.splitHearing(
                "01.12.2026, 10:20 , Зал судебных заседаний № 5079");
        assertEquals("01.12.2026, 10:20", hi.dateTime());
        assertEquals("Зал судебных заседаний № 5079", hi.hall());
    }

    @Test
    void dateTimeOnly() {
        var hi = KadSearchClient.splitHearing("15.09.2026, 10:30");
        assertEquals("15.09.2026, 10:30", hi.dateTime());
        assertEquals("", hi.hall());
    }

    @Test
    void blankIsEmpty() {
        var hi = KadSearchClient.splitHearing("  ");
        assertEquals("", hi.dateTime());
        assertEquals("", hi.hall());
    }
}
