package net.agentensemble.web;

/**
 * Abstraction over a connected WebSocket session. Package-private to allow testing without
 * starting a real server; production code wraps Javalin's {@code WsContext}.
 */
interface WsSession {

    /** Returns the unique session identifier. */
    String id();

    /** Returns true if the underlying WebSocket connection is currently open. */
    boolean isOpen();

    /**
     * Sends a text message over this session. If the session is closed, the message is silently
     * dropped and an error is logged.
     *
     * @param message the JSON text to send; must not be null
     */
    void send(String message);
}
