/**
 * Review Gate E2E test -- with-review scenario.
 *
 * Exercises the human-in-the-loop review flow as a single comprehensive test:
 *   Playwright browser -> viz SPA -> WebSocket -> Java WebDashboard -> WebReviewHandler
 *
 * The Java E2E server (port 7330) runs a one-task ensemble with
 * ReviewPolicy.AFTER_EVERY_TASK. The server's runWithReviewScenario() waits until
 * a browser WebSocket client is connected before starting the ensemble, ensuring
 * the review_requested broadcast reaches the live browser (not a late-join scenario).
 *
 * Design note: all assertions live in a single test to avoid state-sharing across
 * tests against the same server process. The review gate is a one-shot event -- once
 * resolved (Approve clicked), no subsequent test can trigger it again. A single
 * comprehensive test avoids the need to reset server state between cases.
 *
 * Flow verified:
 *   1. Browser connects to port 7330 via WebSocket.
 *   2. Server detects the connection and starts the ensemble.
 *   3. Task completes instantly (stub model). Server blocks on the review gate.
 *   4. review_requested is broadcast to the live browser.
 *   5. Review panel appears with correct task description and output.
 *   6. User clicks Approve (review_decision CONTINUE sent via WebSocket).
 *   7. Panel disappears. Ensemble completes. Header shows "1 / 1 tasks".
 */

import { test, expect, type Page } from '@playwright/test';

const REVIEW_PORT = 7330;
const SERVER_WS_URL = `ws://localhost:${REVIEW_PORT}/ws`;

/** Navigate to the live dashboard connected to the review scenario server. */
async function navigateToReviewDashboard(page: Page) {
  await page.goto(`/live?server=${encodeURIComponent(SERVER_WS_URL)}`);
}

// ========================
// Full review gate flow
// ========================

test('review gate: panel appears, shows correct content, Approve completes the ensemble', async ({
  page,
}) => {
  await navigateToReviewDashboard(page);

  // Step 1: Confirm the browser has connected.
  // The server's waitForClientConnection() polls /api/status and starts the ensemble
  // only after this connection is established.
  await expect(page.getByTestId('connection-status-label')).toHaveText('Connected', {
    timeout: 15_000,
  });

  // Step 2: Review panel appears after the task completes and review_requested fires.
  await expect(page.getByTestId('review-panel')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('heading', { name: 'Review Required' })).toBeVisible();

  // Step 3: Verify panel content.
  // Task description should contain the task text from E2eTestServer.runWithReviewScenario()
  await expect(page.getByTestId('task-description')).toContainText('press release');

  // Task output should contain the stub model's canned response
  await expect(page.getByTestId('task-output')).toContainText('AgentEnsemble announces v3.0');

  // Countdown timer label is visible (panel is in 'view' mode)
  await expect(page.getByTestId('countdown-label')).toBeVisible();

  // All three action buttons are present
  await expect(page.getByTestId('approve-button')).toBeVisible();
  await expect(page.getByTestId('edit-button')).toBeVisible();
  await expect(page.getByTestId('exit-early-button')).toBeVisible();

  // Step 4: Click Approve -- sends review_decision CONTINUE over WebSocket.
  await page.getByTestId('approve-button').click();

  // Step 5: Panel disappears immediately (optimistic removal in LiveServerContext.sendDecision).
  await expect(page.getByTestId('review-panel')).not.toBeVisible({ timeout: 10_000 });

  // Step 6: Ensemble completes (1 task total). Header shows final task count.
  await expect(page.getByTestId('live-header-task-progress')).toHaveText('1 / 1 tasks', {
    timeout: 30_000,
  });
});
