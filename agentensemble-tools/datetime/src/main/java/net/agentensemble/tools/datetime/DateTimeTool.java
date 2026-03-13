package net.agentensemble.tools.datetime;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that provides date and time operations.
 *
 * <p>Supported commands:
 *
 * <ul>
 *   <li>{@code now} -- current date and time in UTC
 *   <li>{@code now in <timezone>} -- current date and time in the specified timezone
 *   <li>{@code today} -- current date in UTC
 *   <li>{@code today in <timezone>} -- current date in the specified timezone
 *   <li>{@code <date> + <N> <unit>} -- add a duration to a date (units: days, weeks, months, years)
 *   <li>{@code <date> - <N> <unit>} -- subtract a duration from a date
 *   <li>{@code <datetime> + <N> <unit>} -- add a duration to a datetime (units include hours,
 *       minutes, seconds in addition to date units)
 *   <li>{@code convert <datetime> from <tz> to <tz>} -- convert a datetime between timezones
 * </ul>
 *
 * <p>Timezone IDs use the IANA format (e.g., {@code America/New_York}, {@code Europe/London},
 * {@code UTC}).
 *
 * <h2>Why this tool uses the legacy string-input pattern</h2>
 *
 * <p>This tool intentionally extends {@link net.agentensemble.tool.AbstractAgentTool} and
 * accepts a plain {@code String} command rather than a typed record. The command language is
 * a compact, human-readable DSL ({@code "now in America/New_York"},
 * {@code "2024-01-01 + 5 days"}). Decomposing it into individual record fields would make
 * the interface more verbose without improving the LLM's ability to use it correctly.
 *
 * <p>This makes {@code DateTimeTool} a reference example of when the legacy string-input
 * style is the right choice -- particularly when the input IS a natural command or expression
 * string. For tools with multiple unrelated parameters, see
 * {@link net.agentensemble.tool.AbstractTypedAgentTool}.
 */
public final class DateTimeTool extends AbstractAgentTool {

    // Pattern: "now in <timezone>"
    private static final Pattern NOW_IN_TZ = Pattern.compile("^now\\s+in\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    // Pattern: "today in <timezone>"
    private static final Pattern TODAY_IN_TZ = Pattern.compile("^today\\s+in\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    // Pattern: "<date-or-datetime> +/- <N> <unit>"
    private static final Pattern ARITHMETIC = Pattern.compile("^(.+?)\\s*([+-])\\s*(\\d+)\\s+(\\w+)\\s*$");

    // Pattern: "convert <datetime> from <tz> to <tz>"
    private static final Pattern CONVERT =
            Pattern.compile("^convert\\s+(.+?)\\s+from\\s+(\\S+)\\s+to\\s+(\\S+)$", Pattern.CASE_INSENSITIVE);

    /** Formatter that always includes seconds -- avoids Java's omission of trailing :00. */
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Clock clock;

    /** Creates a DateTimeTool using the system clock in UTC. */
    public DateTimeTool() {
        this(Clock.systemUTC());
    }

    /** Package-private constructor for testing with a controllable clock. */
    DateTimeTool(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "datetime";
    }

    @Override
    public String description() {
        return "Provides date and time operations. Commands: "
                + "'now' (current UTC datetime), "
                + "'now in <timezone>' (current datetime in timezone), "
                + "'today' (current date), "
                + "'today in <timezone>' (current date in timezone), "
                + "'<date> +/- <N> days|weeks|months|years' (date arithmetic), "
                + "'<datetime> +/- <N> hours|minutes|seconds' (datetime arithmetic), "
                + "'convert <datetime> from <tz> to <tz>' (timezone conversion). "
                + "Timezone IDs use IANA format, e.g. 'America/New_York', 'Europe/London', 'UTC'.";
    }

    @Override
    protected ToolResult doExecute(String input) {
        if (input == null || input.isBlank()) {
            return ToolResult.failure("Command must not be blank");
        }
        String trimmed = input.trim();
        try {
            return dispatch(trimmed);
        } catch (DateTimeException e) {
            return ToolResult.failure("Date/time error: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private ToolResult dispatch(String input) {
        if (input.equalsIgnoreCase("now")) {
            return handleNow(ZoneId.of("UTC"));
        }

        Matcher nowInTz = NOW_IN_TZ.matcher(input);
        if (nowInTz.matches()) {
            ZoneId zone = parseZoneId(nowInTz.group(1).trim());
            return handleNow(zone);
        }

        if (input.equalsIgnoreCase("today")) {
            return handleToday(ZoneId.of("UTC"));
        }

        Matcher todayInTz = TODAY_IN_TZ.matcher(input);
        if (todayInTz.matches()) {
            ZoneId zone = parseZoneId(todayInTz.group(1).trim());
            return handleToday(zone);
        }

        Matcher convert = CONVERT.matcher(input);
        if (convert.matches()) {
            return handleConvert(
                    convert.group(1).trim(),
                    convert.group(2).trim(),
                    convert.group(3).trim());
        }

        Matcher arithmetic = ARITHMETIC.matcher(input);
        if (arithmetic.matches()) {
            return handleArithmetic(
                    arithmetic.group(1).trim(),
                    arithmetic.group(2),
                    Integer.parseInt(arithmetic.group(3)),
                    arithmetic.group(4).toLowerCase(Locale.ROOT));
        }

        return ToolResult.failure("Unrecognized command. Supported: 'now', 'now in <timezone>', 'today', "
                + "'today in <timezone>', '<date> +/- <N> <unit>', "
                + "'convert <datetime> from <tz> to <tz>'");
    }

    private ToolResult handleNow(ZoneId zone) {
        ZonedDateTime now = Instant.now(clock).atZone(zone);
        String output = DATETIME_FORMATTER.format(now.toLocalDateTime()) + " " + zone.getId();
        return ToolResult.success(output);
    }

    private ToolResult handleToday(ZoneId zone) {
        LocalDate today = Instant.now(clock).atZone(zone).toLocalDate();
        return ToolResult.success(today.toString());
    }

    private ToolResult handleConvert(String datetimeStr, String fromTzStr, String toTzStr) {
        ZoneId fromZone = parseZoneId(fromTzStr);
        ZoneId toZone = parseZoneId(toTzStr);
        LocalDateTime ldt = parseLocalDateTime(datetimeStr);
        ZonedDateTime source = ldt.atZone(fromZone);
        ZonedDateTime target = source.withZoneSameInstant(toZone);
        String output = DATETIME_FORMATTER.format(target.toLocalDateTime()) + " " + toZone.getId();
        return ToolResult.success(output);
    }

    private ToolResult handleArithmetic(String dateTimeStr, String operator, int amount, String unit) {
        boolean isDatetime = dateTimeStr.contains("T") || dateTimeStr.contains("Z");
        boolean subtract = "-".equals(operator);
        int delta = subtract ? -amount : amount;

        if (isDatetime) {
            ZonedDateTime zdt = parseZonedDateTime(dateTimeStr);
            ZonedDateTime result = applyDateTimeDelta(zdt, delta, unit);
            return ToolResult.success(DATETIME_FORMATTER.format(result.toLocalDateTime()) + "Z");
        } else {
            LocalDate date = parseLocalDate(dateTimeStr);
            LocalDate result = applyDateDelta(date, delta, unit);
            return ToolResult.success(result.toString());
        }
    }

    private LocalDate parseLocalDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format: '" + s + "'. Expected yyyy-MM-dd");
        }
    }

    private LocalDateTime parseLocalDateTime(String s) {
        // Strip trailing Z if present
        String normalized = s.endsWith("Z") ? s.substring(0, s.length() - 1) : s;
        try {
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid datetime format: '" + s + "'. Expected yyyy-MM-ddTHH:mm:ss[Z]");
        }
    }

    private ZonedDateTime parseZonedDateTime(String s) {
        if (s.endsWith("Z")) {
            return Instant.parse(s).atZone(ZoneId.of("UTC"));
        }
        try {
            return ZonedDateTime.parse(s);
        } catch (DateTimeParseException e) {
            LocalDateTime ldt = parseLocalDateTime(s);
            return ldt.atZone(ZoneId.of("UTC"));
        }
    }

    private ZoneId parseZoneId(String zoneStr) {
        try {
            return ZoneId.of(zoneStr);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown timezone: '" + zoneStr + "'");
        }
    }

    private LocalDate applyDateDelta(LocalDate date, int amount, String unit) {
        return switch (unit) {
            case "day", "days" -> date.plusDays(amount);
            case "week", "weeks" -> date.plusWeeks(amount);
            case "month", "months" -> date.plusMonths(amount);
            case "year", "years" -> date.plusYears(amount);
            default -> throw new IllegalArgumentException(
                    "Unsupported date unit: '" + unit + "'. Use days, weeks, months, or years");
        };
    }

    private ZonedDateTime applyDateTimeDelta(ZonedDateTime zdt, int amount, String unit) {
        return switch (unit) {
            case "hour", "hours" -> zdt.plusHours(amount);
            case "minute", "minutes" -> zdt.plusMinutes(amount);
            case "second", "seconds" -> zdt.plusSeconds(amount);
            case "day", "days" -> zdt.plusDays(amount);
            case "week", "weeks" -> zdt.plusWeeks(amount);
            case "month", "months" -> zdt.plusMonths(amount);
            case "year", "years" -> zdt.plusYears(amount);
            default -> throw new IllegalArgumentException("Unsupported datetime unit: '" + unit + "'");
        };
    }
}
