/**
 * ReviewApprovalPanel: displays a pending human-review request and collects
 * the user's decision (Approve, Edit, or Exit Early).
 *
 * Rendered by LivePage when liveState.pendingReviews.length > 0. The parent
 * passes the oldest pending review (pendingReviews[0]) and the count of
 * additional reviews waiting behind it. When the user submits a decision,
 * sendDecision is called, which also performs an optimistic removal of the
 * review from pendingReviews in LiveServerContext.
 *
 * Timeout countdown:
 *   A CSS-animated progress bar (ae-countdown-bar) provides smooth visual
 *   feedback driven by animation-duration and animation-delay inline styles.
 *   A JS setInterval (1 s tick) updates the human-readable countdown text.
 *   When the countdown reaches zero, the panel displays a "Timed out" message
 *   for 2 seconds and then hides itself. The client-side countdown is advisory
 *   only; the server applies the actual timeout action independently and sends
 *   a review_timed_out message to remove the review from the queue.
 *
 * Review queue:
 *   When multiple reviews are pending (parallel workflow), the parent passes
 *   additionalPendingCount > 0 and a "+N pending" badge is shown below the
 *   panel. The parent uses key={review.reviewId} so this component remounts
 *   cleanly (resetting all state including the countdown) for each new review.
 */

import { useEffect, useRef, useState } from 'react';
import type { LiveReviewRequest } from '../../types/live.js';

// ========================
// Types
// ========================

/** Internal display mode of the panel. */
type PanelMode = 'view' | 'edit' | 'exit-confirm' | 'timed-out';

/** Props for ReviewApprovalPanel. */
export interface ReviewApprovalPanelProps {
  /** The current (oldest) pending review to display. */
  review: LiveReviewRequest;
  /**
   * Number of additional reviews waiting in the queue after this one.
   * Shown as "+N pending" badge when greater than zero.
   */
  additionalPendingCount: number;
  /**
   * Sends a review decision to the server and optimistically removes the review
   * from pendingReviews in the local LiveState.
   */
  sendDecision: (
    reviewId: string,
    decision: 'CONTINUE' | 'EDIT' | 'EXIT_EARLY',
    revisedOutput?: string,
  ) => void;
}

// ========================
// Helpers
// ========================

/**
 * Format remaining milliseconds as M:SS (e.g. 298000 -> "4:58").
 * Always returns a non-negative display value.
 */
function formatCountdown(ms: number): string {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

/**
 * Extract the human-readable timing word from a server timing string.
 * "AFTER_EXECUTION" -> "AFTER", "BEFORE_EXECUTION" -> "BEFORE", etc.
 */
function formatTiming(timing: string): string {
  return timing.split('_')[0];
}

// ========================
// Component
// ========================

/**
 * Review approval panel displayed when a human-review gate fires during a
 * live ensemble run.
 *
 * The component is self-contained: it manages its own countdown timer, display
 * mode (view / edit / exit-confirm / timed-out), and visibility state. It
 * renders null when dismissed to let the parent tree stay mounted.
 */
export default function ReviewApprovalPanel({
  review,
  additionalPendingCount,
  sendDecision,
}: ReviewApprovalPanelProps) {
  const [mode, setMode] = useState<PanelMode>('view');
  const [editText, setEditText] = useState(review.taskOutput);
  const [isVisible, setIsVisible] = useState(true);

  // Compute the remaining countdown at mount time.
  // Captured in a ref so the useEffect below reads the initial value and does
  // not re-run on re-renders (the effect must start the timer exactly once).
  const initialRemainingRef = useRef(
    Math.max(0, review.timeoutMs - (Date.now() - review.receivedAt)),
  );

  // Countdown text displayed in the label (updated every 1 s by setInterval).
  const [remainingMs, setRemainingMs] = useState(initialRemainingRef.current);

  // Ref for the 2-second close timer after countdown reaches zero, so we can
  // cancel it on unmount and avoid the "state update on unmounted component" warning.
  const timedOutCloseRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // ========================
  // Countdown effect (runs once at mount)
  // ========================

  useEffect(() => {
    const initial = initialRemainingRef.current;

    // Already timed out (late-join or zero timeout): skip directly to timed-out mode.
    if (initial <= 0) {
      setMode('timed-out');
      timedOutCloseRef.current = setTimeout(() => setIsVisible(false), 2000);
      return () => {
        if (timedOutCloseRef.current !== null) {
          clearTimeout(timedOutCloseRef.current);
        }
      };
    }

    const interval = setInterval(() => {
      setRemainingMs((prev) => {
        const next = Math.max(0, prev - 1000);
        if (next === 0) {
          clearInterval(interval);
          setMode('timed-out');
          timedOutCloseRef.current = setTimeout(() => setIsVisible(false), 2000);
        }
        return next;
      });
    }, 1000);

    return () => {
      clearInterval(interval);
      if (timedOutCloseRef.current !== null) {
        clearTimeout(timedOutCloseRef.current);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ========================
  // Action handlers
  // ========================

  function handleApprove() {
    sendDecision(review.reviewId, 'CONTINUE');
    setIsVisible(false);
  }

  function handleEditSubmit() {
    sendDecision(review.reviewId, 'EDIT', editText);
    setIsVisible(false);
  }

  function handleExitEarlyConfirm() {
    sendDecision(review.reviewId, 'EXIT_EARLY');
    setIsVisible(false);
  }

  // ========================
  // Render
  // ========================

  if (!isVisible) return null;

  const timingLabel = formatTiming(review.timing);
  const countdownText = formatCountdown(remainingMs);
  const autoLabel =
    review.onTimeout === 'CONTINUE'
      ? `Auto-continue in ${countdownText}`
      : `Auto-exit in ${countdownText}`;

  // Negative animation-delay fast-forwards the CSS bar to the already-elapsed position.
  const elapsedMs = Date.now() - review.receivedAt;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Review Required"
      data-testid="review-panel"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
    >
      <div className="relative w-full max-w-lg rounded-lg bg-white shadow-xl dark:bg-gray-800">
        {/* ---- Header ---- */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4 dark:border-gray-700">
          <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">
            Review Required
          </h2>
          <span
            className="rounded bg-amber-100 px-2 py-0.5 text-xs font-medium uppercase tracking-wide text-amber-800 dark:bg-amber-900/40 dark:text-amber-300"
            data-testid="timing-label"
          >
            {timingLabel}
          </span>
        </div>

        {/* ---- Body ---- */}
        <div className="px-6 py-4">
          {/* Task description */}
          <p className="mb-1 text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">
            Task
          </p>
          <p
            className="mb-4 text-sm font-medium text-gray-900 dark:text-gray-100"
            data-testid="task-description"
          >
            {review.taskDescription}
          </p>

          {/* Custom review prompt (only rendered when provided) */}
          {review.prompt !== null && (
            <p
              className="mb-4 text-sm italic text-gray-600 dark:text-gray-300"
              data-testid="review-prompt"
            >
              {review.prompt}
            </p>
          )}

          {/* Task output / edit textarea / timed-out message */}
          <p className="mb-1 text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">
            Output
          </p>

          {mode === 'edit' ? (
            <textarea
              data-testid="edit-textarea"
              className="h-40 w-full resize-none rounded border border-gray-300 bg-gray-50 p-2 text-sm text-gray-900 focus:outline-none focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100"
              value={editText}
              onChange={(e) => setEditText(e.target.value)}
            />
          ) : mode === 'timed-out' ? (
            <p
              className="rounded bg-gray-100 p-3 text-sm italic text-gray-600 dark:bg-gray-700/60 dark:text-gray-300"
              data-testid="timed-out-message"
            >
              {review.onTimeout === 'CONTINUE' ? 'Timed out -- continuing' : 'Timed out -- exiting'}
            </p>
          ) : (
            <pre
              className="scrollbar-thin max-h-40 overflow-y-auto whitespace-pre-wrap rounded bg-gray-100 p-3 text-sm text-gray-800 dark:bg-gray-700/60 dark:text-gray-200"
              data-testid="task-output"
            >
              {review.taskOutput}
            </pre>
          )}

          {/* Exit early confirmation message */}
          {mode === 'exit-confirm' && (
            <p
              className="mt-3 text-sm font-medium text-red-600 dark:text-red-400"
              data-testid="exit-confirm-message"
            >
              Are you sure? This will stop the pipeline.
            </p>
          )}
        </div>

        {/* ---- Countdown progress bar ---- */}
        {mode !== 'timed-out' && (
          <div className="relative h-1 w-full overflow-hidden bg-gray-200 dark:bg-gray-700">
            <div
              data-testid="countdown-bar"
              className="ae-countdown-bar absolute inset-y-0 left-0 bg-amber-500"
              style={{
                animationDuration: `${review.timeoutMs}ms`,
                animationDelay: `-${elapsedMs}ms`,
              }}
            />
          </div>
        )}

        {/* ---- Countdown label + action buttons ---- */}
        <div className="flex flex-col gap-3 px-6 py-4">
          {/* Countdown label (hidden in timed-out mode; the message replaces it) */}
          {mode !== 'timed-out' && (
            <p
              className="text-xs text-gray-500 dark:text-gray-400"
              data-testid="countdown-label"
            >
              {autoLabel}
            </p>
          )}

          {/* View mode buttons */}
          {mode === 'view' && (
            <div className="flex gap-2">
              <button
                type="button"
                onClick={handleApprove}
                className="flex-1 rounded bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-green-500"
              >
                Approve
              </button>
              <button
                type="button"
                onClick={() => {
                  setEditText(review.taskOutput);
                  setMode('edit');
                }}
                className="flex-1 rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                Edit
              </button>
              <button
                type="button"
                onClick={() => setMode('exit-confirm')}
                className="flex-1 rounded bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-red-500"
              >
                Exit Early
              </button>
            </div>
          )}

          {/* Edit mode buttons */}
          {mode === 'edit' && (
            <div className="flex gap-2">
              <button
                type="button"
                onClick={handleEditSubmit}
                className="flex-1 rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                Submit
              </button>
              <button
                type="button"
                onClick={() => setMode('view')}
                className="flex-1 rounded bg-gray-200 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-300 focus:outline-none focus:ring-2 focus:ring-gray-400 dark:bg-gray-700 dark:text-gray-200 dark:hover:bg-gray-600"
              >
                Cancel
              </button>
            </div>
          )}

          {/* Exit Early confirmation buttons */}
          {mode === 'exit-confirm' && (
            <div className="flex gap-2">
              <button
                type="button"
                data-testid="confirm-exit-button"
                onClick={handleExitEarlyConfirm}
                className="flex-1 rounded bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-red-500"
              >
                Confirm Exit
              </button>
              <button
                type="button"
                onClick={() => setMode('view')}
                className="flex-1 rounded bg-gray-200 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-300 focus:outline-none focus:ring-2 focus:ring-gray-400 dark:bg-gray-700 dark:text-gray-200 dark:hover:bg-gray-600"
              >
                Cancel
              </button>
            </div>
          )}
        </div>

        {/* ---- Queue badge ---- */}
        {additionalPendingCount > 0 && (
          <div
            className="border-t border-gray-200 px-6 py-2 text-center text-xs text-gray-500 dark:border-gray-700 dark:text-gray-400"
            data-testid="queue-badge"
          >
            +{additionalPendingCount} pending
          </div>
        )}
      </div>
    </div>
  );
}
