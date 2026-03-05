import React, { useEffect, useMemo, useRef, useState } from 'react';
import type { ExecutionTrace, TaskTrace, LlmInteraction, ToolCallTrace } from '../types/trace.js';
import { parseDurationMs, formatDuration, formatInstant, formatTokenCount } from '../utils/parser.js';
import { getAgentColor, withOpacity, getToolOutcomeColor, LLM_CALL_COLOR, seedAgentColors } from '../utils/colors.js';
import { TaskMetricsBadges, RunSummaryBadges } from '../components/shared/MetricsBadge.js';

interface TimelineViewProps {
  trace: ExecutionTrace;
}

type SelectedItem =
  | { kind: 'task'; trace: TaskTrace }
  | { kind: 'llm'; task: TaskTrace; interaction: LlmInteraction }
  | { kind: 'tool'; task: TaskTrace; llm: LlmInteraction; tool: ToolCallTrace };

const LABEL_WIDTH = 160;
const LANE_HEIGHT = 70;
const LANE_PADDING = 8;
const HEADER_HEIGHT = 30;

/**
 * Timeline View page: renders a Gantt-chart-style execution timeline.
 *
 * Shows one horizontal swimlane per agent. Each swimlane contains task bars,
 * with LLM interaction sub-bars and tool call markers overlaid.
 * Clicking any element opens the detail panel.
 */
export default function TimelineView({ trace }: TimelineViewProps) {
  const [selected, setSelected] = useState<SelectedItem | null>(null);
  const [zoom, setZoom] = useState(1);
  const svgRef = useRef<SVGSVGElement>(null);

  // Seed agent colors from the trace's agent list
  useEffect(() => {
    seedAgentColors(trace.agents.map((a) => a.role));
  }, [trace]);

  // Compute timeline bounds
  const runStart = useMemo(() => new Date(trace.startedAt).getTime(), [trace]);
  const runEnd = useMemo(() => new Date(trace.completedAt).getTime(), [trace]);
  const totalMs = useMemo(() => Math.max(runEnd - runStart, 1), [runStart, runEnd]);

  // Group task traces by agent role (preserving agent order from trace.agents)
  const agentRoles = useMemo(() => {
    const orderedRoles = trace.agents.map((a) => a.role);
    // Include any roles from taskTraces not in agents list (defensive)
    const extraRoles = trace.taskTraces
      .map((t) => t.agentRole)
      .filter((r) => !orderedRoles.includes(r));
    return [...new Set([...orderedRoles, ...extraRoles])];
  }, [trace]);

  const tasksByAgent = useMemo(() => {
    const map = new Map<string, TaskTrace[]>();
    for (const role of agentRoles) {
      map.set(role, []);
    }
    for (const task of trace.taskTraces) {
      const list = map.get(task.agentRole) ?? [];
      list.push(task);
      map.set(task.agentRole, list);
    }
    return map;
  }, [trace, agentRoles]);

  const svgWidth = 900;
  const chartWidth = (svgWidth - LABEL_WIDTH) * zoom;
  const svgHeight = agentRoles.length * LANE_HEIGHT + HEADER_HEIGHT + 10;

  // Convert a timestamp to SVG x coordinate in the chart area
  const toX = (ms: number) => {
    const fraction = (ms - runStart) / totalMs;
    return LABEL_WIDTH + fraction * chartWidth;
  };

  // Time axis ticks
  const ticks = useMemo(() => {
    const tickCount = Math.min(10, Math.max(4, Math.floor(chartWidth / 80)));
    return Array.from({ length: tickCount + 1 }, (_, i) => {
      const ms = runStart + (totalMs * i) / tickCount;
      return { x: toX(ms), label: `+${formatDuration((ms - runStart))}` };
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runStart, totalMs, chartWidth]);

  return (
    <div className="flex flex-1 overflow-hidden">
      {/* Main timeline area */}
      <div className="flex flex-1 flex-col overflow-hidden">
        {/* Run summary header */}
        <div className="border-b border-gray-200 bg-white px-4 py-2 dark:border-gray-700 dark:bg-gray-900">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <span className="text-xs font-semibold text-gray-700 dark:text-gray-300">
                {trace.workflow} &middot; {trace.taskTraces.length} tasks &middot; {trace.agents.length} agents
              </span>
              <span className="text-xs text-gray-400 dark:text-gray-500">
                {formatInstant(trace.startedAt)}
              </span>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-xs text-gray-500">Zoom:</span>
              <input
                type="range"
                min={0.5}
                max={5}
                step={0.1}
                value={zoom}
                onChange={(e) => setZoom(Number(e.target.value))}
                className="w-24"
              />
              <span className="text-xs text-gray-500">{zoom.toFixed(1)}x</span>
            </div>
          </div>
          <div className="mt-2">
            <RunSummaryBadges metrics={trace.metrics} totalDuration={trace.totalDuration} />
          </div>
        </div>

        {/* Capture mode notice */}
        {trace.captureMode === 'OFF' && (
          <div className="border-b border-amber-200 bg-amber-50 px-4 py-1.5 text-xs text-amber-700 dark:border-amber-800 dark:bg-amber-900/20 dark:text-amber-400">
            CaptureMode is OFF. LLM conversation history is not available.
            Set <code>captureMode(CaptureMode.STANDARD)</code> on your Ensemble for richer data.
          </div>
        )}

        {/* Scrollable SVG timeline */}
        <div className="flex-1 overflow-auto scrollbar-thin">
          <svg
            ref={svgRef}
            width={Math.max(svgWidth, LABEL_WIDTH + chartWidth + 20)}
            height={svgHeight}
            className="bg-white dark:bg-gray-900"
          >
            {/* Lane backgrounds */}
            {agentRoles.map((role, i) => {
              const y = HEADER_HEIGHT + i * LANE_HEIGHT;
              const color = getAgentColor(role);
              return (
                <g key={role}>
                  <rect
                    x={0}
                    y={y}
                    width={LABEL_WIDTH}
                    height={LANE_HEIGHT}
                    fill={withOpacity(color.bg, 0.08)}
                    stroke="#E5E7EB"
                    strokeWidth={0.5}
                  />
                  <rect
                    x={LABEL_WIDTH}
                    y={y}
                    width={chartWidth + 100}
                    height={LANE_HEIGHT}
                    fill={i % 2 === 0 ? '#FAFAFA' : '#F5F7FA'}
                    className="dark:fill-gray-900"
                  />
                  {/* Horizontal lane divider */}
                  <line
                    x1={0}
                    y1={y + LANE_HEIGHT}
                    x2={LABEL_WIDTH + chartWidth + 100}
                    y2={y + LANE_HEIGHT}
                    stroke="#E5E7EB"
                    strokeWidth={0.5}
                  />
                  {/* Agent label */}
                  <text
                    x={LABEL_WIDTH - 8}
                    y={y + LANE_HEIGHT / 2}
                    textAnchor="end"
                    dominantBaseline="middle"
                    fontSize={11}
                    fontWeight={600}
                    fill={color.bg}
                  >
                    {role.length > 18 ? role.slice(0, 16) + '...' : role}
                  </text>

                  {/* Task bars for this agent */}
                  {(tasksByAgent.get(role) ?? []).map((task) => {
                    const taskStart = new Date(task.startedAt).getTime();
                    const taskEnd = new Date(task.completedAt).getTime();
                    const x = toX(taskStart);
                    const w = Math.max(4, toX(taskEnd) - toX(taskStart));
                    const barY = y + LANE_PADDING;
                    const barH = LANE_HEIGHT - LANE_PADDING * 2;
                    const isSelected =
                      selected?.kind === 'task' && selected.trace === task;

                    return (
                      <g
                        key={task.taskDescription}
                        onClick={() => setSelected({ kind: 'task', trace: task })}
                        className="cursor-pointer"
                      >
                        {/* Task bar */}
                        <rect
                          x={x}
                          y={barY}
                          width={w}
                          height={barH}
                          rx={4}
                          fill={withOpacity(color.bg, isSelected ? 0.9 : 0.6)}
                          stroke={isSelected ? color.bg : 'transparent'}
                          strokeWidth={2}
                        />

                        {/* LLM interaction sub-bars */}
                        {task.llmInteractions.map((llm) => {
                          const llmStart = new Date(llm.startedAt).getTime();
                          const llmEnd = new Date(llm.completedAt).getTime();
                          const lx = toX(llmStart);
                          const lw = Math.max(2, toX(llmEnd) - toX(llmStart));
                          const isLlmSelected =
                            selected?.kind === 'llm' && selected.interaction === llm;
                          return (
                            <g
                              key={llm.iterationIndex}
                              onClick={(e) => {
                                e.stopPropagation();
                                setSelected({ kind: 'llm', task, interaction: llm });
                              }}
                            >
                              <rect
                                x={lx}
                                y={barY + 2}
                                width={lw}
                                height={barH - 4}
                                rx={2}
                                fill={LLM_CALL_COLOR}
                                fillOpacity={isLlmSelected ? 0.9 : 0.6}
                              />
                              {/* Tool call dots within LLM bar */}
                              {llm.toolCalls.map((tool, ti) => {
                                const toolStart = new Date(tool.startedAt).getTime();
                                const toolEnd = new Date(tool.completedAt).getTime();
                                const tx = toX(toolStart);
                                const tw = Math.max(2, toX(toolEnd) - toX(toolStart));
                                const isToolSelected =
                                  selected?.kind === 'tool' && selected.tool === tool;
                                return (
                                  <rect
                                    key={ti}
                                    x={tx}
                                    y={barY + barH - 6}
                                    width={tw}
                                    height={4}
                                    rx={1}
                                    fill={getToolOutcomeColor(tool.outcome)}
                                    opacity={isToolSelected ? 1 : 0.8}
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      setSelected({ kind: 'tool', task, llm, tool });
                                    }}
                                  />
                                );
                              })}
                            </g>
                          );
                        })}

                        {/* Task label (only if bar is wide enough) */}
                        {w > 50 && (
                          <text
                            x={x + 4}
                            y={barY + barH / 2}
                            dominantBaseline="middle"
                            fontSize={9}
                            fill="white"
                            className="pointer-events-none select-none"
                          >
                            {task.taskDescription.slice(0, Math.floor(w / 6))}
                            {task.taskDescription.length > Math.floor(w / 6) ? '...' : ''}
                          </text>
                        )}
                      </g>
                    );
                  })}
                </g>
              );
            })}

            {/* Time axis */}
            <rect x={0} y={0} width={LABEL_WIDTH + chartWidth + 100} height={HEADER_HEIGHT} fill="white" className="dark:fill-gray-900" />
            {ticks.map((tick) => (
              <g key={tick.label}>
                <line
                  x1={tick.x}
                  y1={HEADER_HEIGHT - 8}
                  x2={tick.x}
                  y2={svgHeight}
                  stroke="#E5E7EB"
                  strokeWidth={0.5}
                  strokeDasharray="4 4"
                />
                <text
                  x={tick.x}
                  y={HEADER_HEIGHT / 2}
                  textAnchor="middle"
                  dominantBaseline="middle"
                  fontSize={9}
                  fill="#9CA3AF"
                >
                  {tick.label}
                </text>
              </g>
            ))}

            {/* Legend */}
            <g transform={`translate(${LABEL_WIDTH}, ${svgHeight - 20})`}>
              <LegendItem x={0} color={LLM_CALL_COLOR} label="LLM call" />
              <LegendItem x={80} color="#22C55E" label="Tool (OK)" />
              <LegendItem x={160} color="#EF4444" label="Tool (Error)" />
              <LegendItem x={240} color="#9CA3AF" label="Tool (Skipped)" />
            </g>
          </svg>
        </div>
      </div>

      {/* Detail panel */}
      {selected && (
        <div className="flex w-80 flex-col overflow-hidden border-l border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-900">
          {selected.kind === 'task' && <TaskDetailPanel task={selected.trace} onClose={() => setSelected(null)} />}
          {selected.kind === 'llm' && <LlmDetailPanel task={selected.task} interaction={selected.interaction} onClose={() => setSelected(null)} />}
          {selected.kind === 'tool' && <ToolDetailPanel task={selected.task} tool={selected.tool} onClose={() => setSelected(null)} />}
        </div>
      )}
    </div>
  );
}

// ========================
// Detail panel sub-components
// ========================

function PanelHeader({ title, subtitle, onClose }: { title: string; subtitle?: string; onClose: () => void }) {
  return (
    <div className="flex items-start justify-between border-b border-gray-200 p-3 dark:border-gray-700">
      <div>
        <p className="text-xs font-semibold text-gray-800 dark:text-gray-200">{title}</p>
        {subtitle && <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{subtitle}</p>}
      </div>
      <button onClick={onClose} className="ml-2 shrink-0 text-gray-400 hover:text-gray-600 dark:hover:text-gray-200">&#x2715;</button>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="border-b border-gray-100 px-3 py-2.5 dark:border-gray-800">
      <p className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-gray-400 dark:text-gray-500">{title}</p>
      {children}
    </div>
  );
}

function TaskDetailPanel({ task, onClose }: { task: TaskTrace; onClose: () => void }) {
  const durationMs = parseDurationMs(task.duration);
  return (
    <>
      <PanelHeader title={task.agentRole} subtitle={formatDuration(durationMs)} onClose={onClose} />
      <div className="flex-1 overflow-y-auto scrollbar-thin">
        <Section title="Task">
          <p className="text-xs text-gray-700 dark:text-gray-300">{task.taskDescription}</p>
        </Section>
        <Section title="Metrics">
          <TaskMetricsBadges metrics={task.metrics} />
        </Section>
        {task.prompts && (
          <Section title="System Prompt">
            <p className="whitespace-pre-wrap font-mono text-xs text-gray-600 dark:text-gray-400">{task.prompts.systemPrompt}</p>
          </Section>
        )}
        {task.prompts && (
          <Section title="User Prompt">
            <p className="whitespace-pre-wrap font-mono text-xs text-gray-600 dark:text-gray-400">{task.prompts.userPrompt}</p>
          </Section>
        )}
        <Section title="Final Output">
          <p className="whitespace-pre-wrap text-xs text-gray-700 dark:text-gray-300">{task.finalOutput || '(empty)'}</p>
        </Section>
        {task.llmInteractions.length > 0 && (
          <Section title={`LLM Iterations (${task.llmInteractions.length})`}>
            <div className="space-y-1">
              {task.llmInteractions.map((llm) => (
                <div key={llm.iterationIndex} className="rounded bg-gray-50 px-2 py-1 text-xs dark:bg-gray-800">
                  <span className="font-medium text-indigo-600 dark:text-indigo-400">#{llm.iterationIndex + 1}</span>
                  <span className="ml-2 text-gray-500">{llm.responseType.replace('_', ' ').toLowerCase()}</span>
                  <span className="ml-2 text-gray-400">{formatDuration(parseDurationMs(llm.latency))}</span>
                  {llm.toolCalls.length > 0 && (
                    <span className="ml-2 text-emerald-600 dark:text-emerald-400">{llm.toolCalls.length} tool{llm.toolCalls.length > 1 ? 's' : ''}</span>
                  )}
                  {(llm.inputTokens !== -1 || llm.outputTokens !== -1) && (
                    <span className="ml-2 text-gray-400">{formatTokenCount(llm.inputTokens)}+{formatTokenCount(llm.outputTokens)} tok</span>
                  )}
                </div>
              ))}
            </div>
          </Section>
        )}
      </div>
    </>
  );
}

function LlmDetailPanel({ task, interaction, onClose }: { task: TaskTrace; interaction: LlmInteraction; onClose: () => void }) {
  return (
    <>
      <PanelHeader
        title={`LLM Call #${interaction.iterationIndex + 1}`}
        subtitle={`${task.agentRole} · ${formatDuration(parseDurationMs(interaction.latency))}`}
        onClose={onClose}
      />
      <div className="flex-1 overflow-y-auto scrollbar-thin">
        <Section title="Summary">
          <div className="space-y-1 text-xs">
            <div className="flex justify-between"><span className="text-gray-500">Response type</span><span className="font-medium">{interaction.responseType.replace('_', ' ')}</span></div>
            <div className="flex justify-between"><span className="text-gray-500">Latency</span><span>{formatDuration(parseDurationMs(interaction.latency))}</span></div>
            {interaction.inputTokens !== -1 && (
              <div className="flex justify-between"><span className="text-gray-500">Tokens</span><span>{formatTokenCount(interaction.inputTokens)} in / {formatTokenCount(interaction.outputTokens)} out</span></div>
            )}
          </div>
        </Section>

        {interaction.messages.length > 0 && (
          <Section title={`Message History (${interaction.messages.length})`}>
            <div className="space-y-2">
              {interaction.messages.map((msg, i) => (
                <div key={i} className={['rounded p-2 text-xs', msg.role === 'system' ? 'bg-gray-100 dark:bg-gray-800' : msg.role === 'user' ? 'bg-blue-50 dark:bg-blue-900/20' : msg.role === 'assistant' ? 'bg-indigo-50 dark:bg-indigo-900/20' : 'bg-emerald-50 dark:bg-emerald-900/20'].join(' ')}>
                  <p className="mb-1 font-semibold uppercase tracking-wider text-gray-400" style={{ fontSize: 9 }}>{msg.role}</p>
                  {msg.content && <p className="whitespace-pre-wrap text-gray-700 dark:text-gray-300">{msg.content}</p>}
                  {msg.toolCalls.length > 0 && (
                    <div className="space-y-1">
                      {msg.toolCalls.map((tc, j) => (
                        <div key={j} className="rounded bg-white/60 p-1 dark:bg-gray-900/60">
                          <span className="font-mono font-semibold text-indigo-600 dark:text-indigo-400">{tc.name}</span>
                          <pre className="mt-0.5 overflow-auto text-gray-600 dark:text-gray-400">{tc.arguments}</pre>
                        </div>
                      ))}
                    </div>
                  )}
                  {msg.toolName && <p className="font-mono text-emerald-600 dark:text-emerald-400">Tool: {msg.toolName}</p>}
                </div>
              ))}
            </div>
          </Section>
        )}

        {interaction.responseText && (
          <Section title="Response">
            <p className="whitespace-pre-wrap text-xs text-gray-700 dark:text-gray-300">{interaction.responseText}</p>
          </Section>
        )}

        {interaction.toolCalls.length > 0 && (
          <Section title={`Tool Calls (${interaction.toolCalls.length})`}>
            <div className="space-y-2">
              {interaction.toolCalls.map((tool, i) => (
                <div key={i} className="rounded border border-gray-200 p-2 text-xs dark:border-gray-700">
                  <div className="flex items-center justify-between">
                    <span className="font-mono font-semibold text-emerald-600 dark:text-emerald-400">{tool.toolName}</span>
                    <span className={['rounded px-1 py-0.5 text-xs', tool.outcome === 'SUCCESS' ? 'bg-green-100 text-green-700' : tool.outcome === 'ERROR' ? 'bg-red-100 text-red-700' : 'bg-gray-100 text-gray-500'].join(' ')}>{tool.outcome}</span>
                  </div>
                  <pre className="mt-1 overflow-auto text-gray-600 dark:text-gray-400">{tool.arguments}</pre>
                  {tool.result && <p className="mt-1 border-t border-gray-100 pt-1 text-gray-600 dark:text-gray-400">{tool.result}</p>}
                </div>
              ))}
            </div>
          </Section>
        )}
      </div>
    </>
  );
}

function ToolDetailPanel({ task, tool, onClose }: { task: TaskTrace; tool: ToolCallTrace; onClose: () => void }) {
  return (
    <>
      <PanelHeader
        title={tool.toolName}
        subtitle={`${task.agentRole} · ${formatDuration(parseDurationMs(tool.duration))}`}
        onClose={onClose}
      />
      <div className="flex-1 overflow-y-auto scrollbar-thin">
        <Section title="Outcome">
          <span className={['rounded px-2 py-0.5 text-xs font-semibold', tool.outcome === 'SUCCESS' ? 'bg-green-100 text-green-700' : tool.outcome === 'ERROR' ? 'bg-red-100 text-red-700' : 'bg-gray-100 text-gray-500'].join(' ')}>{tool.outcome}</span>
        </Section>
        <Section title="Arguments">
          <pre className="overflow-auto whitespace-pre-wrap font-mono text-xs text-gray-700 dark:text-gray-300">{tool.arguments}</pre>
        </Section>
        {tool.parsedInput && (
          <Section title="Parsed Input">
            <pre className="overflow-auto whitespace-pre-wrap font-mono text-xs text-gray-700 dark:text-gray-300">{JSON.stringify(tool.parsedInput, null, 2)}</pre>
          </Section>
        )}
        {tool.result && (
          <Section title="Result">
            <pre className="overflow-auto whitespace-pre-wrap font-mono text-xs text-gray-700 dark:text-gray-300">{tool.result}</pre>
          </Section>
        )}
        <Section title="Timing">
          <div className="space-y-1 text-xs">
            <div className="flex justify-between"><span className="text-gray-500">Start</span><span>{formatInstant(tool.startedAt)}</span></div>
            <div className="flex justify-between"><span className="text-gray-500">End</span><span>{formatInstant(tool.completedAt)}</span></div>
            <div className="flex justify-between"><span className="text-gray-500">Duration</span><span>{formatDuration(parseDurationMs(tool.duration))}</span></div>
          </div>
        </Section>
      </div>
    </>
  );
}

function LegendItem({ x, color, label }: { x: number; color: string; label: string }) {
  return (
    <g transform={`translate(${x}, 0)`}>
      <rect x={0} y={4} width={12} height={8} rx={2} fill={color} />
      <text x={15} y={12} fontSize={9} fill="#9CA3AF">{label}</text>
    </g>
  );
}
