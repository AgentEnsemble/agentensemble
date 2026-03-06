import { render, screen, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ReviewApprovalPanel from '../components/live/ReviewApprovalPanel.js';
import type { LiveReviewRequest } from '../types/live.js';

// ========================
// Fixtures
// ========================

const FIXED_NOW = 1_741_000_000_000;

const baseReview: LiveReviewRequest = {
  reviewId: 'review-abc',
  taskDescription: 'Research AI trends',
  taskOutput: 'The AI landscape in 2025 has been dominated by large language models.',
  timing: 'AFTER_EXECUTION',
  prompt: null,
  timeoutMs: 300_000,
  onTimeout: 'CONTINUE',
  receivedAt: FIXED_NOW,
};

// ========================
// Test suite
// ========================

describe('ReviewApprovalPanel', () => {
  let sendDecision: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    sendDecision = vi.fn();
    vi.useFakeTimers();
    vi.setSystemTime(FIXED_NOW);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // ========================
  // Initial render
  // ========================

  describe('initial render', () => {
    it('renders task description, output, timing label, and all three action buttons', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.getByTestId('task-description')).toHaveTextContent('Research AI trends');
      expect(screen.getByTestId('task-output')).toHaveTextContent(
        'The AI landscape in 2025',
      );
      expect(screen.getByTestId('timing-label')).toHaveTextContent('AFTER');
      expect(screen.getByRole('button', { name: /approve/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /edit/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /exit early/i })).toBeInTheDocument();
    });

    it('renders custom review prompt when non-null', () => {
      const review = { ...baseReview, prompt: 'Please verify the facts carefully.' };
      render(
        <ReviewApprovalPanel
          review={review}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.getByTestId('review-prompt')).toHaveTextContent(
        'Please verify the facts carefully.',
      );
    });

    it('does not render the review prompt element when prompt is null', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.queryByTestId('review-prompt')).not.toBeInTheDocument();
    });

    it('formats timing label as the first segment of the timing string', () => {
      const review = { ...baseReview, timing: 'BEFORE_EXECUTION' };
      render(
        <ReviewApprovalPanel
          review={review}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.getByTestId('timing-label')).toHaveTextContent('BEFORE');
    });

    it('formats DURING_EXECUTION timing label', () => {
      const review = { ...baseReview, timing: 'DURING_EXECUTION' };
      render(
        <ReviewApprovalPanel
          review={review}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.getByTestId('timing-label')).toHaveTextContent('DURING');
    });
  });

  // ========================
  // Approve action
  // ========================

  describe('approve action', () => {
    it('sends review_decision with CONTINUE and the reviewId', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: /approve/i }));
      expect(sendDecision).toHaveBeenCalledOnce();
      expect(sendDecision).toHaveBeenCalledWith('review-abc', 'CONTINUE');
    });

    it('closes the panel after approving', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: /approve/i }));
      expect(screen.queryByTestId('review-panel')).not.toBeInTheDocument();
    });
  });

  // ========================
  // Edit action
  // ========================

  describe('edit action', () => {
    it('clicking Edit shows a textarea pre-filled with the current task output', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: /edit/i }));
      const textarea = screen.getByTestId('edit-textarea') as HTMLTextAreaElement;
      expect(textarea.value).toBe(
        'The AI landscape in 2025 has been dominated by large language models.',
      );
    });

    it('clicking Edit hides the read-only output display', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: /edit/i }));
      expect(screen.queryByTestId('task-output')).not.toBeInTheDocument();
    });

    it('Submit sends review_decision with EDIT and the revised text', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: /edit/i }));
      const textarea = screen.getByTestId('edit-textarea');
      fireEvent.change(textarea, { target: { value: 'Revised output text.' } });
      fireEvent.click(screen.getByRole('button', { name: /submit/i }));
      expect(sendDecision).toHaveBeenCalledWith('review-abc', 'EDIT', 'Revised output text.');
    });

    it('closes the panel after submitting an edit', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: /edit/i }));
      fireEvent.click(screen.getByRole('button', { name: /submit/i }));
      expect(screen.queryByTestId('review-panel')).not.toBeInTheDocument();
    });

    it('Cancel returns to the read-only view without sending a decision', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: /edit/i }));
      expect(screen.getByTestId('edit-textarea')).toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: /cancel/i }));

      expect(screen.queryByTestId('edit-textarea')).not.toBeInTheDocument();
      expect(screen.getByTestId('task-output')).toBeInTheDocument();
      expect(sendDecision).not.toHaveBeenCalled();
    });
  });

  // ========================
  // Exit Early action
  // ========================

  describe('exit early action', () => {
    it('clicking Exit Early shows a confirmation message', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: /exit early/i }));
      expect(screen.getByTestId('exit-confirm-message')).toBeInTheDocument();
    });

    it('confirming exit early sends review_decision with EXIT_EARLY', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: /exit early/i }));
      fireEvent.click(screen.getByTestId('confirm-exit-button'));
      expect(sendDecision).toHaveBeenCalledOnce();
      expect(sendDecision).toHaveBeenCalledWith('review-abc', 'EXIT_EARLY');
    });

    it('closes the panel after confirming exit early', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: /exit early/i }));
      fireEvent.click(screen.getByTestId('confirm-exit-button'));
      expect(screen.queryByTestId('review-panel')).not.toBeInTheDocument();
    });

    it('Cancel from exit-confirm returns to view without sending a decision', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: /exit early/i }));
      expect(screen.getByTestId('exit-confirm-message')).toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: /cancel/i }));

      expect(screen.queryByTestId('exit-confirm-message')).not.toBeInTheDocument();
      expect(sendDecision).not.toHaveBeenCalled();
    });
  });

  // ========================
  // Timeout countdown
  // ========================

  describe('timeout countdown', () => {
    it('starts at timeoutMs and shows Auto-continue label for CONTINUE timeout', () => {
      // 300000ms = 5:00
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.getByTestId('countdown-label')).toHaveTextContent('Auto-continue in 5:00');
    });

    it('shows Auto-exit label when onTimeout is EXIT_EARLY', () => {
      // 120000ms = 2:00
      const review = { ...baseReview, timeoutMs: 120_000, onTimeout: 'EXIT_EARLY' };
      render(
        <ReviewApprovalPanel
          review={review}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.getByTestId('countdown-label')).toHaveTextContent('Auto-exit in 2:00');
    });

    it('decrements the timer text each second', () => {
      // 300000ms = 5:00 initial; after 30 ticks = 270000ms = 4:30
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      act(() => {
        vi.advanceTimersByTime(30_000);
      });
      expect(screen.getByTestId('countdown-label')).toHaveTextContent('Auto-continue in 4:30');
    });

    it('shows "Timed out -- continuing" when CONTINUE timeout reaches zero', () => {
      const review = { ...baseReview, timeoutMs: 5_000, onTimeout: 'CONTINUE' };
      render(
        <ReviewApprovalPanel
          review={review}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      act(() => {
        vi.advanceTimersByTime(5_000);
      });
      expect(screen.getByTestId('timed-out-message')).toHaveTextContent('Timed out -- continuing');
    });

    it('shows "Timed out -- exiting" when EXIT_EARLY timeout reaches zero', () => {
      const review = { ...baseReview, timeoutMs: 5_000, onTimeout: 'EXIT_EARLY' };
      render(
        <ReviewApprovalPanel
          review={review}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      act(() => {
        vi.advanceTimersByTime(5_000);
      });
      expect(screen.getByTestId('timed-out-message')).toHaveTextContent('Timed out -- exiting');
    });

    it('panel is still visible immediately after countdown reaches zero', () => {
      const review = { ...baseReview, timeoutMs: 5_000, onTimeout: 'CONTINUE' };
      render(
        <ReviewApprovalPanel
          review={review}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      act(() => {
        vi.advanceTimersByTime(5_000);
      });
      expect(screen.getByTestId('review-panel')).toBeInTheDocument();
    });

    it('closes the panel 2 seconds after the countdown reaches zero', () => {
      const review = { ...baseReview, timeoutMs: 5_000, onTimeout: 'CONTINUE' };
      render(
        <ReviewApprovalPanel
          review={review}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      act(() => {
        vi.advanceTimersByTime(5_000);
      });
      act(() => {
        vi.advanceTimersByTime(2_000);
      });
      expect(screen.queryByTestId('review-panel')).not.toBeInTheDocument();
    });

    it('does not call sendDecision when the countdown reaches zero', () => {
      const review = { ...baseReview, timeoutMs: 5_000, onTimeout: 'CONTINUE' };
      render(
        <ReviewApprovalPanel
          review={review}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      act(() => {
        vi.advanceTimersByTime(7_000);
      });
      expect(sendDecision).not.toHaveBeenCalled();
    });
  });

  // ========================
  // Review queue badge
  // ========================

  describe('review queue badge', () => {
    it('does not show badge when additionalPendingCount is 0', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.queryByTestId('queue-badge')).not.toBeInTheDocument();
    });

    it('shows "+1 pending" badge when additionalPendingCount is 1', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={1}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.getByTestId('queue-badge')).toHaveTextContent('+1 pending');
    });

    it('shows "+3 pending" badge when additionalPendingCount is 3', () => {
      render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={3}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.getByTestId('queue-badge')).toHaveTextContent('+3 pending');
    });

    it('displays the current review prop and badge count independently', () => {
      // Simulates the parent showing a second review after the first is resolved.
      // The parent passes a new review prop (key-based remounting in real usage).
      const review1 = { ...baseReview, reviewId: 'r1', taskDescription: 'Task One' };
      const review2 = { ...baseReview, reviewId: 'r2', taskDescription: 'Task Two' };

      const { rerender } = render(
        <ReviewApprovalPanel
          review={review1}
          additionalPendingCount={2}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.getByTestId('task-description')).toHaveTextContent('Task One');
      expect(screen.getByTestId('queue-badge')).toHaveTextContent('+2 pending');

      rerender(
        <ReviewApprovalPanel
          review={review2}
          additionalPendingCount={1}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.getByTestId('task-description')).toHaveTextContent('Task Two');
      expect(screen.getByTestId('queue-badge')).toHaveTextContent('+1 pending');

      rerender(
        <ReviewApprovalPanel
          review={review2}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.queryByTestId('queue-badge')).not.toBeInTheDocument();
    });
  });

  // ========================
  // review_timed_out server message -- parent unmounts the panel
  // ========================

  describe('review_timed_out from server', () => {
    it('panel disappears when the parent unmounts it (pendingReviews becomes empty)', () => {
      // The liveReducer removes the review from pendingReviews when review_timed_out
      // arrives. The parent renders the panel only when pendingReviews.length > 0,
      // so the panel unmounts when the server drives the timeout independently of
      // the client countdown.
      const { unmount } = render(
        <ReviewApprovalPanel
          review={baseReview}
          additionalPendingCount={0}
          sendDecision={sendDecision}
        />,
      );
      expect(screen.getByTestId('review-panel')).toBeInTheDocument();
      unmount();
      expect(screen.queryByTestId('review-panel')).not.toBeInTheDocument();
    });
  });
});
