package net.agentensemble.web.protocol;

/**
 * Priority level for cross-ensemble work requests.
 *
 * <p>Requests are processed by priority ({@link #CRITICAL} highest, {@link #LOW} lowest).
 * Within the same priority, FIFO ordering applies.
 *
 * @see WorkRequest
 */
public enum Priority {

    /** Highest priority -- immediate processing required. */
    CRITICAL,

    /** High priority -- processed before NORMAL and LOW. */
    HIGH,

    /** Default priority. */
    NORMAL,

    /** Lowest priority -- processed when capacity is available. */
    LOW
}
