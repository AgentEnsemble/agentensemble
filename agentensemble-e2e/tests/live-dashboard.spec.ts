/**
 * Live Dashboard E2E tests -- sequential scenario.
 *
 * These tests exercise the full vertical slice:
 *   Playwright browser -> Vite-served viz SPA -> WebSocket -> Java WebDashboard
 *
 * The Java E2E server (port 7329) runs a two-task sequential ensemble with a
 * StubChatModel. All events are captured in the server-side snapshot so that
 * a browser connecting after the ensemble completes still receives the full
 * event history via the hello message.
 *
 * Tests verify:
 *   - Connection status bar shows "Connected"
 *   - Ensemble metadata (ensemble ID, workflow) appears in the header
 *   - Task progress reaches "2 / 2 tasks"
 *   - Two task bars appear in the live timeline
 *   - All task bars have status="completed"
 *   - Switching to the Flow view renders the DAG graph
 */

import { test, expect, type Page } from '@playwright/test';

const SEQUENTIAL_PORT = 7329;
const SERVER_WS_URL = `ws://localhost:${SEQUENTIAL_PORT}/ws`;

/** Navigate to the live dashboard connected to the sequential scenario server. */
async function navigateToLiveDashboard(page: Page) {
  await page.goto(`/live?server=${encodeURIComponent(SERVER_WS_URL)}`);
}

// ========================
// Connection
// ========================

test('live dashboard connects to the server and shows Connected status', async ({ page }) => {
  await navigateToLiveDashboard(page);

  await expect(page.getByTestId('connection-status-bar')).toBeVisible();
  await expect(page.getByTestId('connection-status-label')).toHaveText('Connected', {
    timeout: 15_000,
  });
  await expect(page.getByTestId('connection-status-url')).toContainText('localhost:7329');
});

// ========================
// Ensemble metadata
// ========================

test('live header shows ensemble metadata after connection', async ({ page }) => {
  await navigateToLiveDashboard(page);

  // Wait for connected state first
  await expect(page.getByTestId('connection-status-label')).toHaveText('Connected', {
    timeout: 15_000,
  });

  // Ensemble ID and workflow should appear once ensemble_started is received
  await expect(page.getByTestId('live-header-ensemble-id')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('live-header-workflow')).toHaveText('SEQUENTIAL', {
    timeout: 15_000,
  });
});

// ========================
// Task progress
// ========================

test('task progress reaches 2 / 2 tasks after both tasks complete', async ({ page }) => {
  await navigateToLiveDashboard(page);

  await expect(page.getByTestId('live-header-task-progress')).toHaveText('2 / 2 tasks', {
    timeout: 30_000,
  });
});

// ========================
// Timeline task bars
// ========================

test('timeline renders a task bar for each completed task', async ({ page }) => {
  await navigateToLiveDashboard(page);

  // Wait for the first bar to appear before counting
  await expect(page.getByTestId('live-task-bar').first()).toBeVisible({ timeout: 30_000 });

  // Exactly two task bars -- one per task
  await expect(page.getByTestId('live-task-bar')).toHaveCount(2, { timeout: 15_000 });
});

test('all task bars have data-task-status="completed"', async ({ page }) => {
  await navigateToLiveDashboard(page);

  // Wait for both tasks to finish
  await expect(page.getByTestId('live-header-task-progress')).toHaveText('2 / 2 tasks', {
    timeout: 30_000,
  });

  const bars = page.getByTestId('live-task-bar');
  await expect(bars).toHaveCount(2);

  for (const bar of await bars.all()) {
    await expect(bar).toHaveAttribute('data-task-status', 'completed');
  }
});

// ========================
// Flow view
// ========================

test('switching to Flow view renders the live DAG graph', async ({ page }) => {
  await navigateToLiveDashboard(page);

  // Wait for data before switching views
  await expect(page.getByTestId('live-header-task-progress')).toHaveText('2 / 2 tasks', {
    timeout: 30_000,
  });

  // Click the Flow tab
  await page.getByTestId('live-view-tab-flow').click();

  await expect(page.getByTestId('live-flow-view')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('live-flow-status-bar')).toBeVisible();
  await expect(page.getByTestId('live-flow-status-bar')).toContainText('SEQUENTIAL');
});
