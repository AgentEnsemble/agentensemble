import { describe, it, expect, beforeEach } from 'vitest';
import {
  getAgentColor,
  resetAgentColors,
  seedAgentColors,
  getToolOutcomeColor,
  withOpacity,
  TOOL_CALL_SUCCESS_COLOR,
  TOOL_CALL_ERROR_COLOR,
  TOOL_CALL_SKIPPED_COLOR,
} from '../utils/colors.js';

beforeEach(() => {
  resetAgentColors();
});

describe('getAgentColor', () => {
  it('returns a color object with required fields', () => {
    const color = getAgentColor('Researcher');
    expect(color).toHaveProperty('bg');
    expect(color).toHaveProperty('border');
    expect(color).toHaveProperty('text');
    expect(color).toHaveProperty('light');
    expect(color.bg).toMatch(/^#[0-9A-Fa-f]{6}$/);
  });

  it('returns the same color for the same role', () => {
    const first = getAgentColor('Writer');
    const second = getAgentColor('Writer');
    expect(first).toBe(second);
  });

  it('assigns different colors to different roles', () => {
    const a = getAgentColor('Researcher');
    const b = getAgentColor('Writer');
    expect(a.bg).not.toBe(b.bg);
  });

  it('assigns colors deterministically after reset', () => {
    const firstRun = getAgentColor('Agent A');
    resetAgentColors();
    const secondRun = getAgentColor('Agent A');
    expect(firstRun.bg).toBe(secondRun.bg);
  });
});

describe('seedAgentColors', () => {
  it('seeds colors in registration order', () => {
    seedAgentColors(['Researcher', 'Writer', 'Analyst']);
    const r = getAgentColor('Researcher');
    const w = getAgentColor('Writer');
    const a = getAgentColor('Analyst');
    expect(r.bg).not.toBe(w.bg);
    expect(w.bg).not.toBe(a.bg);
  });

  it('resets existing colors before seeding', () => {
    getAgentColor('First'); // assigns index 0
    seedAgentColors(['Another']); // should reset and assign index 0 to 'Another'
    const another = getAgentColor('Another');
    getAgentColor('First'); // gets next index after 'Another'
    // 'Another' should have the same color 'First' had before reset (index 0)
    resetAgentColors();
    const index0 = getAgentColor('TestAgent');
    expect(another.bg).toBe(index0.bg);
  });
});

describe('getToolOutcomeColor', () => {
  it('returns green for SUCCESS', () => {
    expect(getToolOutcomeColor('SUCCESS')).toBe(TOOL_CALL_SUCCESS_COLOR);
  });

  it('returns red for ERROR', () => {
    expect(getToolOutcomeColor('ERROR')).toBe(TOOL_CALL_ERROR_COLOR);
  });

  it('returns gray for SKIPPED', () => {
    expect(getToolOutcomeColor('SKIPPED')).toBe(TOOL_CALL_SKIPPED_COLOR);
  });

  it('returns gray for unknown outcome', () => {
    expect(getToolOutcomeColor('UNKNOWN')).toBe('#9CA3AF');
  });
});

describe('withOpacity', () => {
  it('returns rgba string with correct components', () => {
    const result = withOpacity('#FF0000', 0.5);
    expect(result).toBe('rgba(255, 0, 0, 0.5)');
  });

  it('handles blue hex correctly', () => {
    const result = withOpacity('#3B82F6', 1);
    expect(result).toBe('rgba(59, 130, 246, 1)');
  });

  it('handles zero opacity', () => {
    const result = withOpacity('#000000', 0);
    expect(result).toBe('rgba(0, 0, 0, 0)');
  });
});
