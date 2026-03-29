/**
 * CodingProgressPanel: shows per-agent coding statistics in the bottom panel.
 *
 * Displays:
 * - Files created/modified/deleted grouped by agent
 * - Total lines added/removed per agent (green +N / red -N)
 * - Most recently edited file per agent
 * - Summary row with totals across all agents
 */

import { useMemo } from 'react';
import type { LiveFileChange } from '../../types/live.js';
import { getAgentColor } from '../../utils/colors.js';

interface CodingProgressPanelProps {
  fileChanges: LiveFileChange[];
}

interface AgentCodingStats {
  role: string;
  filesCreated: number;
  filesModified: number;
  filesDeleted: number;
  linesAdded: number;
  linesRemoved: number;
  lastFile: string | null;
}

export default function CodingProgressPanel({ fileChanges }: CodingProgressPanelProps) {
  const stats = useMemo(() => {
    const byRole = new Map<string, AgentCodingStats>();

    for (const change of fileChanges) {
      let stat = byRole.get(change.agentRole);
      if (!stat) {
        stat = {
          role: change.agentRole,
          filesCreated: 0,
          filesModified: 0,
          filesDeleted: 0,
          linesAdded: 0,
          linesRemoved: 0,
          lastFile: null,
        };
        byRole.set(change.agentRole, stat);
      }

      if (change.changeType === 'CREATED') stat.filesCreated++;
      else if (change.changeType === 'MODIFIED') stat.filesModified++;
      else if (change.changeType === 'DELETED') stat.filesDeleted++;

      stat.linesAdded += change.linesAdded;
      stat.linesRemoved += change.linesRemoved;
      stat.lastFile = change.filePath;
    }

    return Array.from(byRole.values());
  }, [fileChanges]);

  if (fileChanges.length === 0) {
    return (
      <div className="flex h-full items-center justify-center">
        <span className="text-xs text-gray-400 dark:text-gray-500">
          No file changes yet
        </span>
      </div>
    );
  }

  const totalAdded = stats.reduce((s, a) => s + a.linesAdded, 0);
  const totalRemoved = stats.reduce((s, a) => s + a.linesRemoved, 0);
  const totalFiles = new Set(fileChanges.map((c) => c.filePath)).size;

  return (
    <div className="h-full overflow-auto">
      <table className="w-full text-left text-[11px]">
        <thead>
          <tr className="border-b border-gray-200 text-[10px] font-semibold uppercase tracking-wider text-gray-500 dark:border-gray-700 dark:text-gray-400">
            <th className="py-1 pr-3">Agent</th>
            <th className="py-1 pr-3">Files</th>
            <th className="py-1 pr-3">Lines</th>
            <th className="py-1">Last File</th>
          </tr>
        </thead>
        <tbody>
          {stats.map((stat) => {
            const color = getAgentColor(stat.role);
            return (
              <tr key={stat.role} className="border-b border-gray-100 dark:border-gray-800">
                <td className="py-1.5 pr-3">
                  <div className="flex items-center gap-1.5">
                    <div className="h-2 w-2 rounded-full" style={{ backgroundColor: color.bg }} />
                    <span className="font-medium text-gray-800 dark:text-gray-200">{stat.role}</span>
                  </div>
                </td>
                <td className="py-1.5 pr-3 text-gray-600 dark:text-gray-400">
                  {stat.filesCreated > 0 && <span className="text-green-600">+{stat.filesCreated} </span>}
                  {stat.filesModified > 0 && <span className="text-blue-600">~{stat.filesModified} </span>}
                  {stat.filesDeleted > 0 && <span className="text-red-600">-{stat.filesDeleted}</span>}
                </td>
                <td className="py-1.5 pr-3">
                  <span className="text-green-600">+{stat.linesAdded}</span>
                  {stat.linesRemoved > 0 && (
                    <span className="ml-1 text-red-600">-{stat.linesRemoved}</span>
                  )}
                </td>
                <td className="max-w-[200px] truncate py-1.5 font-mono text-[10px] text-gray-500 dark:text-gray-400">
                  {stat.lastFile}
                </td>
              </tr>
            );
          })}
        </tbody>
        <tfoot>
          <tr className="font-semibold text-gray-700 dark:text-gray-300">
            <td className="py-1.5 pr-3">Total</td>
            <td className="py-1.5 pr-3">{totalFiles} files</td>
            <td className="py-1.5 pr-3">
              <span className="text-green-600">+{totalAdded}</span>
              {totalRemoved > 0 && <span className="ml-1 text-red-600">-{totalRemoved}</span>}
            </td>
            <td />
          </tr>
        </tfoot>
      </table>
    </div>
  );
}
