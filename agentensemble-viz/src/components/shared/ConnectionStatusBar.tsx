/**
 * ConnectionStatusBar: displays the current WebSocket connection status with a
 * colored indicator dot and a text label.
 *
 * - connected:    green dot, "Connected"
 * - connecting:   yellow/amber dot (pulsing), "Connecting..."
 * - disconnected: red dot, "Disconnected"
 * - error:        red dot, "Connection error"
 */

import type { ConnectionStatus } from '../../types/live.js';

interface ConnectionStatusBarProps {
  status: ConnectionStatus;
  /** WebSocket server URL to display (e.g. ws://localhost:7329/ws). */
  serverUrl: string | null;
  className?: string;
}

/**
 * Thin status bar showing the live connection state with a colored dot.
 *
 * The dot color and label are driven by `status`:
 * - connected    -> green dot, "Connected"
 * - connecting   -> amber dot (with CSS pulse animation), "Connecting..."
 * - disconnected -> red dot, "Disconnected"
 * - error        -> red dot, "Connection error"
 */
export default function ConnectionStatusBar({
  status,
  serverUrl,
  className = '',
}: ConnectionStatusBarProps) {
  const { dotClass, label, barClass } = STATUS_DISPLAY[status];

  return (
    <div
      role="status"
      aria-label={`Connection status: ${label}`}
      data-testid="connection-status-bar"
      data-status={status}
      className={[
        'flex items-center gap-2 border-b px-4 py-1.5 text-xs',
        barClass,
        className,
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {/* Status dot */}
      <span
        data-testid="connection-status-dot"
        className={['h-2 w-2 rounded-full shrink-0', dotClass].join(' ')}
        aria-hidden="true"
      />

      {/* Status label */}
      <span className="font-medium" data-testid="connection-status-label">
        {label}
      </span>

      {/* Server URL */}
      {serverUrl && (
        <span className="truncate text-gray-500 dark:text-gray-400" data-testid="connection-status-url">
          {serverUrl}
        </span>
      )}
    </div>
  );
}

// ========================
// Status display config
// ========================

interface StatusDisplay {
  dotClass: string;
  label: string;
  barClass: string;
}

const STATUS_DISPLAY: Record<ConnectionStatus, StatusDisplay> = {
  connected: {
    dotClass: 'bg-green-500',
    label: 'Connected',
    barClass:
      'border-green-200 bg-green-50 text-green-800 dark:border-green-800 dark:bg-green-950/30 dark:text-green-300',
  },
  connecting: {
    dotClass: 'bg-amber-400 ae-pulse',
    label: 'Connecting...',
    barClass:
      'border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-300',
  },
  disconnected: {
    dotClass: 'bg-red-500',
    label: 'Disconnected',
    barClass:
      'border-red-200 bg-red-50 text-red-800 dark:border-red-800 dark:bg-red-950/30 dark:text-red-300',
  },
  error: {
    dotClass: 'bg-red-500',
    label: 'Connection error',
    barClass:
      'border-red-200 bg-red-50 text-red-800 dark:border-red-800 dark:bg-red-950/30 dark:text-red-300',
  },
};
