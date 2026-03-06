import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for AgentEnsemble E2E tests.
 *
 * Architecture:
 *   - The viz SPA (agentensemble-viz) is built and served via Vite preview on port 5173.
 *   - Two Java test servers (agentensemble-e2e module) start on ports 7329 and 7330,
 *     each running a distinct test scenario via WebDashboard + StubChatModel.
 *   - Tests navigate to the viz SPA and pass a ?server=ws://localhost:<port>/ws query
 *     param, which the LivePage component uses to connect the browser WebSocket.
 *
 * This means the full vertical slice exercises:
 *   Browser (Playwright) -> Vite-served viz SPA -> WebSocket -> Java WebDashboard
 *
 * Ports:
 *   5173  -- Vite preview (viz SPA)
 *   7329  -- Java E2E server, sequential scenario (2 tasks, no review)
 *   7330  -- Java E2E server, with-review scenario (1 task + review gate)
 */

const VIZ_PORT = 5173;
const SEQUENTIAL_PORT = 7329;
const REVIEW_PORT = 7330;

export default defineConfig({
  testDir: './tests',

  // Per-test timeout. Review gate tests need extra time for the Java server
  // to boot and the ensemble to fire, plus Playwright interaction.
  timeout: 60_000,

  // Per-assertion timeout (e.g. expect(...).toHaveText(...))
  expect: { timeout: 15_000 },

  // Run tests within each file sequentially. The review-gate tests share server state
  // (the pending review is a one-shot event per server process), so they must not run
  // in parallel against the same server instance.
  fullyParallel: false,
  workers: 1,

  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,

  reporter: process.env.CI
    ? [['github'], ['html', { outputFolder: 'playwright-report', open: 'never' }]]
    : [['list'], ['html', { outputFolder: 'playwright-report', open: 'on-failure' }]],

  use: {
    // Tests navigate to the Vite preview server; the ?server= param connects to the Java backend.
    baseURL: `http://localhost:${VIZ_PORT}`,

    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  webServer: [
    // ========================
    // 1. Viz SPA (Vite preview)
    // ========================
    //
    // Builds the viz and serves it via vite preview on port 5173.
    // reuseExistingServer is enabled locally so developers can pre-build
    // the viz (npm run build in agentensemble-viz) and run tests faster.
    {
      command:
        'cd ../agentensemble-viz && npm run build && npx vite preview --port 5173 --host localhost',
      port: VIZ_PORT,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },

    // ========================
    // 2. Java E2E server -- sequential scenario
    // ========================
    //
    // Boots WebDashboard on port 7329 and immediately fires a two-task sequential
    // ensemble via StubChatModel. All events are snapshotted so late-joining browsers
    // receive the full execution history via the hello message.
    {
      command: '../gradlew :agentensemble-e2e:runE2eServerSequential 2>&1',
      port: SEQUENTIAL_PORT,
      // Always start fresh -- the ensemble fires immediately on startup and the
      // review gate is never triggered, so the server state is stable.
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },

    // ========================
    // 3. Java E2E server -- with-review scenario
    // ========================
    //
    // Boots WebDashboard on port 7330 and fires a one-task ensemble that blocks
    // waiting for a browser review_decision message after the task completes.
    // The review gate is a one-shot event, so always start fresh.
    {
      command: '../gradlew :agentensemble-e2e:runE2eServerWithReview 2>&1',
      port: REVIEW_PORT,
      reuseExistingServer: false,
      timeout: 120_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
});
