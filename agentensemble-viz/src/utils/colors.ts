/**
 * Color utilities for assigning consistent colors to agents, tasks, and event types.
 *
 * Colors are designed to be visually distinct in both light and dark modes,
 * using Tailwind's palette as a reference.
 */

/** Available distinct agent colors (background hex, text contrast class) */
const AGENT_PALETTE: Array<{ bg: string; border: string; text: string; light: string }> = [
  { bg: '#3B82F6', border: '#2563EB', text: '#FFFFFF', light: '#EFF6FF' }, // blue
  { bg: '#10B981', border: '#059669', text: '#FFFFFF', light: '#ECFDF5' }, // emerald
  { bg: '#F59E0B', border: '#D97706', text: '#FFFFFF', light: '#FFFBEB' }, // amber
  { bg: '#EF4444', border: '#DC2626', text: '#FFFFFF', light: '#FEF2F2' }, // red
  { bg: '#8B5CF6', border: '#7C3AED', text: '#FFFFFF', light: '#F5F3FF' }, // violet
  { bg: '#06B6D4', border: '#0891B2', text: '#FFFFFF', light: '#ECFEFF' }, // cyan
  { bg: '#F97316', border: '#EA580C', text: '#FFFFFF', light: '#FFF7ED' }, // orange
  { bg: '#84CC16', border: '#65A30D', text: '#FFFFFF', light: '#F7FEE7' }, // lime
  { bg: '#EC4899', border: '#DB2777', text: '#FFFFFF', light: '#FDF2F8' }, // pink
  { bg: '#14B8A6', border: '#0D9488', text: '#FFFFFF', light: '#F0FDFA' }, // teal
];

const agentColorCache = new Map<string, (typeof AGENT_PALETTE)[0]>();

/**
 * Get a consistent color assignment for an agent role.
 * The same role always maps to the same color within a session.
 */
export function getAgentColor(role: string): (typeof AGENT_PALETTE)[0] {
  if (!agentColorCache.has(role)) {
    const index = agentColorCache.size % AGENT_PALETTE.length;
    agentColorCache.set(role, AGENT_PALETTE[index]);
  }
  return agentColorCache.get(role)!;
}

/**
 * Reset the agent color cache. Useful between page navigations or when loading new files.
 */
export function resetAgentColors(): void {
  agentColorCache.clear();
}

/**
 * Pre-seed the color cache with a known list of agent roles, ensuring that
 * colors are assigned in a deterministic order matching the ensemble's registration order.
 */
export function seedAgentColors(roles: string[]): void {
  resetAgentColors();
  roles.forEach((role) => getAgentColor(role));
}

/**
 * Colors for LLM interaction event types.
 */
export const LLM_CALL_COLOR = '#6366F1'; // indigo
export const TOOL_CALL_SUCCESS_COLOR = '#22C55E'; // green
export const TOOL_CALL_ERROR_COLOR = '#EF4444'; // red
export const TOOL_CALL_SKIPPED_COLOR = '#9CA3AF'; // gray
export const DELEGATION_COLOR = '#F59E0B'; // amber

/**
 * Get a color for a tool call based on its outcome.
 */
export function getToolOutcomeColor(outcome: string): string {
  switch (outcome) {
    case 'SUCCESS':
      return TOOL_CALL_SUCCESS_COLOR;
    case 'ERROR':
      return TOOL_CALL_ERROR_COLOR;
    case 'SKIPPED':
      return TOOL_CALL_SKIPPED_COLOR;
    default:
      return '#9CA3AF';
  }
}

/**
 * Generate an SVG-safe hex color with a given opacity (0-1), returned as rgba string.
 */
export function withOpacity(hex: string, opacity: number): string {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r}, ${g}, ${b}, ${opacity})`;
}
