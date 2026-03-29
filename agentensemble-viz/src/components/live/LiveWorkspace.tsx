/**
 * LiveWorkspace: IDE-like multi-panel layout for the live execution dashboard.
 *
 * Layout:
 * +-----------------------------------------------------------+
 * | Agent    |                              | Right Panel      |
 * | List     |   Main View Area             | (Conversation /  |
 * | Sidebar  |   (Timeline or Flow DAG)     |  Agent Detail)   |
 * |          |                              |                  |
 * |          +------------------------------+------------------+
 * |          |   Bottom Panel (Files / Coding Progress / Metrics) |
 * +----------+----------------------------------------------------+
 *
 * All panels are resizable via react-resizable-panels (v4).
 */

import { useState } from 'react';
import { Panel, Group, Separator } from 'react-resizable-panels';
import type { LiveState } from '../../types/live.js';
import type { LiveView } from './LiveHeader.js';
import AgentListPanel from './AgentListPanel.js';
import ConversationPanel from './ConversationPanel.js';
import CodingProgressPanel from './CodingProgressPanel.js';
import FileExplorer from './FileExplorer.js';
import MetricsDashboard from './MetricsDashboard.js';
import TimelineView from '../../pages/TimelineView.js';
import FlowView from '../../pages/FlowView.js';

/** Tabs available in the bottom panel. */
export type BottomTab = 'files' | 'coding' | 'metrics';

interface LiveWorkspaceProps {
  liveState: LiveState;
  activeView: LiveView;
}

export default function LiveWorkspace({ liveState, activeView }: LiveWorkspaceProps) {
  const [selectedAgent, setSelectedAgent] = useState<string | null>(null);
  const [bottomTab, setBottomTab] = useState<BottomTab>('coding');

  // Find the most recent conversation for the selected agent
  const selectedConversation = selectedAgent
    ? Object.values(liveState.conversations).find((c) => c.agentRole === selectedAgent) ?? null
    : null;

  return (
    <Group orientation="horizontal" className="flex-1">
      {/* Left sidebar: Agent list */}
      <Panel
        defaultSize="15%"
        minSize="10%"
        maxSize="25%"
        collapsible
        className="border-r border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-900"
      >
        <AgentListPanel
          liveState={liveState}
          selectedAgent={selectedAgent}
          onSelectAgent={setSelectedAgent}
        />
      </Panel>

      <Separator className="w-1 bg-gray-200 transition-colors hover:bg-blue-400 dark:bg-gray-700 dark:hover:bg-blue-500" />

      {/* Center + right area */}
      <Panel defaultSize={selectedAgent ? '55%' : '85%'} minSize="40%">
        <Group orientation="vertical">
          {/* Main view */}
          <Panel defaultSize="65%" minSize="30%">
            <div className="h-full">
              {activeView === 'timeline' && <TimelineView trace={null} isLive />}
              {activeView === 'flow' && (
                <FlowView
                  dag={null}
                  trace={null}
                  isLive
                  onNodeClick={(agentRole) => setSelectedAgent(agentRole)}
                />
              )}
            </div>
          </Panel>

          <Separator className="h-1 bg-gray-200 transition-colors hover:bg-blue-400 dark:bg-gray-700 dark:hover:bg-blue-500" />

          {/* Bottom panel: tabs for files, coding progress, metrics */}
          <Panel
            defaultSize="35%"
            minSize="15%"
            collapsible
            className="border-t border-gray-200 dark:border-gray-700"
          >
            <div className="flex h-full flex-col bg-white dark:bg-gray-900">
              {/* Tab bar */}
              <div className="flex shrink-0 items-center gap-1 border-b border-gray-200 px-3 py-1 dark:border-gray-700">
                <BottomTabButton
                  label="Coding"
                  active={bottomTab === 'coding'}
                  onClick={() => setBottomTab('coding')}
                />
                <BottomTabButton
                  label="Files"
                  active={bottomTab === 'files'}
                  onClick={() => setBottomTab('files')}
                />
                <BottomTabButton
                  label="Metrics"
                  active={bottomTab === 'metrics'}
                  onClick={() => setBottomTab('metrics')}
                />
              </div>

              {/* Tab content */}
              <div className="flex-1 overflow-auto p-3">
                {bottomTab === 'coding' && (
                  <CodingProgressPanel fileChanges={liveState.fileChanges} />
                )}
                {bottomTab === 'files' && (
                  <FileExplorer serverUrl={liveState.serverUrl} fileChanges={liveState.fileChanges} />
                )}
                {bottomTab === 'metrics' && (
                  <MetricsDashboard metricsHistory={liveState.metricsHistory} tasks={liveState.tasks} />
                )}
              </div>
            </div>
          </Panel>
        </Group>
      </Panel>

      {/* Right panel: conversation / agent detail (shown when an agent is selected) */}
      {selectedAgent && (
        <>
          <Separator className="w-1 bg-gray-200 transition-colors hover:bg-blue-400 dark:bg-gray-700 dark:hover:bg-blue-500" />
          <Panel
            defaultSize="30%"
            minSize="20%"
            maxSize="50%"
            className="border-l border-gray-200 dark:border-gray-700"
          >
            <div className="flex h-full flex-col bg-white dark:bg-gray-900">
              <div className="flex shrink-0 items-center justify-between border-b border-gray-200 px-3 py-2 dark:border-gray-700">
                <span className="text-xs font-semibold text-gray-700 dark:text-gray-300">
                  {selectedAgent}
                </span>
                <button
                  onClick={() => setSelectedAgent(null)}
                  className="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-800 dark:hover:text-gray-300"
                  title="Close panel"
                >
                  <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>
              <div className="flex-1 overflow-hidden">
                <ConversationPanel
                  conversation={selectedConversation}
                  agentRole={selectedAgent}
                />
              </div>
            </div>
          </Panel>
        </>
      )}
    </Group>
  );
}

function BottomTabButton({
  label,
  active,
  onClick,
}: {
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className={[
        'rounded px-2 py-0.5 text-[10px] font-medium transition-colors',
        active
          ? 'bg-gray-200 text-gray-800 dark:bg-gray-700 dark:text-gray-200'
          : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300',
      ].join(' ')}
    >
      {label}
    </button>
  );
}
