package net.agentensemble.tools.datetime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DateTimeToolTest {

    // Fixed clock: 2024-03-15T12:30:00Z (Friday)
    private static final Instant FIXED_INSTANT = Instant.parse("2024-03-15T12:30:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, UTC);

    private DateTimeTool tool;

    @BeforeEach
    void setUp() {
        tool = new DateTimeTool(FIXED_CLOCK);
    }

    // --- metadata ---

    @Test
    void name_returnDatetime() {
        assertThat(tool.name()).isEqualTo("datetime");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- now ---

    @Test
    void execute_now_returnsCurrentUtcDatetime() {
        var result = tool.execute("now");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("2024-03-15");
        assertThat(result.getOutput()).contains("12:30");
        assertThat(result.getOutput()).containsIgnoringCase("UTC");
    }

    @Test
    void execute_nowInTimezone_returnsCurrentDatetimeInThatZone() {
        var result = tool.execute("now in America/New_York");
        assertThat(result.isSuccess()).isTrue();
        // 2024-03-15 is after the US spring-forward (2024-03-10), so New York is EDT (UTC-4)
        // 12:30 UTC = 08:30 EDT
        assertThat(result.getOutput()).contains("08:30");
        assertThat(result.getOutput()).contains("America/New_York");
    }

    @Test
    void execute_today_returnsCurrentDate() {
        var result = tool.execute("today");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("2024-03-15");
    }

    @Test
    void execute_todayInTimezone_returnsCurrentDateInThatZone() {
        var result = tool.execute("today in Pacific/Auckland");
        assertThat(result.isSuccess()).isTrue();
        // Auckland is UTC+13 in March: 2024-03-15T12:30Z = 2024-03-16T01:30+13:00
        assertThat(result.getOutput()).isEqualTo("2024-03-16");
    }

    // --- date arithmetic ---

    @Test
    void execute_addDays() {
        var result = tool.execute("2024-03-15 + 5 days");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("2024-03-20");
    }

    @Test
    void execute_subtractDays() {
        var result = tool.execute("2024-03-15 - 10 days");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("2024-03-05");
    }

    @Test
    void execute_addWeeks() {
        var result = tool.execute("2024-01-01 + 2 weeks");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("2024-01-15");
    }

    @Test
    void execute_subtractWeeks() {
        var result = tool.execute("2024-01-15 - 1 week");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("2024-01-08");
    }

    @Test
    void execute_addMonths() {
        var result = tool.execute("2024-01-31 + 1 month");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("2024-02-29");
    }

    @Test
    void execute_addYears() {
        var result = tool.execute("2024-03-15 + 1 year");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("2025-03-15");
    }

    @Test
    void execute_addHours() {
        var result = tool.execute("2024-03-15T10:00:00Z + 3 hours");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("13:00:00");
    }

    @Test
    void execute_subtractHours() {
        var result = tool.execute("2024-03-15T10:00:00Z - 2 hours");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("08:00:00");
    }

    @Test
    void execute_addMinutes() {
        var result = tool.execute("2024-03-15T10:00:00Z + 45 minutes");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("10:45:00");
    }

    @Test
    void execute_addSeconds() {
        var result = tool.execute("2024-03-15T10:00:00Z + 90 seconds");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("10:01:30");
    }

    @Test
    void execute_addDaysToDatetime() {
        var result = tool.execute("2024-03-15T10:00:00Z + 2 days");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("2024-03-17");
    }

    @Test
    void execute_addWeeksToDatetime() {
        var result = tool.execute("2024-03-15T10:00:00Z + 1 week");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("2024-03-22");
    }

    @Test
    void execute_addMonthsToDatetime() {
        var result = tool.execute("2024-03-15T10:00:00Z + 1 month");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("2024-04-15");
    }

    @Test
    void execute_addYearsToDatetime() {
        var result = tool.execute("2024-03-15T10:00:00Z + 1 year");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("2025-03-15");
    }

    @Test
    void execute_datetimeWithNoZSuffix_usesUtcFallback() {
        // Input without Z suffix: parseZonedDateTime falls through to LocalDateTime + UTC
        var result = tool.execute("2024-03-15T10:00:00 + 1 hour");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("11:00:00");
    }

    @Test
    void execute_unsupportedDateUnit_returnsFailure() {
        var result = tool.execute("2024-03-15 + 1 fortnight");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("fortnight");
    }

    @Test
    void execute_unsupportedDatetimeUnit_returnsFailure() {
        var result = tool.execute("2024-03-15T10:00:00Z + 1 century");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("century");
    }

    // --- timezone conversion ---

    @Test
    void execute_convertFromUtcToTimezone() {
        var result = tool.execute("convert 2024-03-15T12:00:00Z from UTC to America/Chicago");
        assertThat(result.isSuccess()).isTrue();
        // Chicago is CDT (UTC-5) on 2024-03-15 (DST started 2024-03-10): 12:00 UTC = 07:00 CDT
        assertThat(result.getOutput()).contains("07:00:00");
    }

    @Test
    void execute_convertBetweenTimezones() {
        var result = tool.execute("convert 2024-03-15T09:00:00 from America/New_York to Europe/London");
        assertThat(result.isSuccess()).isTrue();
        // New York is UTC-4 in March (EDT): 09:00 EDT = 13:00 GMT
        assertThat(result.getOutput()).contains("13:00:00");
    }

    // --- failure cases ---

    @Test
    void execute_nullInput_returnsFailure() {
        var result = tool.execute(null);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_blankInput_returnsFailure() {
        var result = tool.execute("  ");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_unknownTimezone_returnsFailure() {
        var result = tool.execute("now in Fake/Timezone");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("timezone");
    }

    @Test
    void execute_invalidDateFormat_returnsFailure() {
        var result = tool.execute("2024-13-99 + 1 day");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_unrecognizedCommand_returnsFailure() {
        var result = tool.execute("some random text");
        assertThat(result.isSuccess()).isFalse();
    }

    // --- default constructor (smoke test) ---

    @Test
    void defaultConstructor_producesWorkingTool() {
        var defaultTool = new DateTimeTool();
        var result = defaultTool.execute("today");
        assertThat(result.isSuccess()).isTrue();
    }

    // --- PreserveStackTrace regression tests ---

    @Test
    void execute_invalidDateFormat_errorMessageContainsOffendingValue() {
        // Regression test: parseLocalDate chains DateTimeParseException as cause.
        // The failure message should contain the invalid value for diagnostics.
        var result = tool.execute("not-a-date + 1 day");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("not-a-date");
    }

    @Test
    void execute_invalidDateTimeFormat_errorMessageContainsOffendingValue() {
        // Regression test: parseLocalDateTime chains DateTimeParseException as cause.
        var result = tool.execute("2024-01-01Tbadtime + 1 hour");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("2024-01-01Tbadtime");
    }

    @Test
    void execute_invalidTimezone_errorMessageContainsOffendingValue() {
        // Regression test: parseZoneId chains DateTimeException as cause.
        var result = tool.execute("now in Not/AReal/Zone");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Not/AReal/Zone");
    }
}
