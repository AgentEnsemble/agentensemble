/**
 * FileExplorer: workspace file tree viewer for the bottom panel.
 *
 * Fetches directory listings from the /api/workspace/files endpoint and
 * renders a lazy-loading tree view. Recently modified files (from LiveState
 * file changes) are highlighted with an indicator.
 */

import { useState, useCallback } from 'react';
import type { LiveFileChange } from '../../types/live.js';
import { getAgentColor } from '../../utils/colors.js';

interface FileExplorerProps {
  /** Base URL of the WebSocket server (e.g. ws://localhost:7329/ws -> http://localhost:7329) */
  serverUrl: string | null;
  /** File changes from the live state for highlighting modified files. */
  fileChanges: LiveFileChange[];
}

interface FileEntry {
  name: string;
  type: 'file' | 'directory';
  size?: number;
  lastModified?: string;
}

interface TreeNode {
  entry: FileEntry;
  path: string;
  children: TreeNode[] | null; // null = not loaded, [] = empty dir
  loading: boolean;
  expanded: boolean;
}

/** Convert WebSocket URL to HTTP base URL for REST calls. */
function wsToHttp(wsUrl: string): string {
  return wsUrl.replace(/^ws:/, 'http:').replace(/^wss:/, 'https:').replace(/\/ws$/, '');
}

export default function FileExplorer({ serverUrl, fileChanges }: FileExplorerProps) {
  const [rootNodes, setRootNodes] = useState<TreeNode[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  const [fileContent, setFileContent] = useState<string | null>(null);

  // Build a set of recently modified file paths for highlighting
  const modifiedFiles = new Map<string, LiveFileChange>();
  for (const change of fileChanges) {
    modifiedFiles.set(change.filePath, change);
  }

  const baseUrl = serverUrl ? wsToHttp(serverUrl) : null;

  const fetchDir = useCallback(async (path: string): Promise<FileEntry[]> => {
    if (!baseUrl) return [];
    const url = `${baseUrl}/api/workspace/files?path=${encodeURIComponent(path)}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`Failed to fetch: ${res.status}`);
    return res.json();
  }, [baseUrl]);

  const fetchFile = useCallback(async (path: string): Promise<string> => {
    if (!baseUrl) return '';
    const url = `${baseUrl}/api/workspace/file?path=${encodeURIComponent(path)}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`Failed to fetch: ${res.status}`);
    return res.text();
  }, [baseUrl]);

  // Load root directory on first render
  const loadRoot = useCallback(async () => {
    try {
      setError(null);
      const entries = await fetchDir('');
      const sorted = entries.sort((a, b) => {
        if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
        return a.name.localeCompare(b.name);
      });
      setRootNodes(
        sorted.map((entry) => ({
          entry,
          path: entry.name,
          children: entry.type === 'directory' ? null : [],
          loading: false,
          expanded: false,
        })),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load');
    }
  }, [fetchDir]);

  const toggleNode = useCallback(
    async (path: string) => {
      const updateNodes = (nodes: TreeNode[]): TreeNode[] =>
        nodes.map((node) => {
          if (node.path === path) {
            if (node.entry.type === 'file') {
              // Load file content
              setSelectedFile(path);
              fetchFile(path).then(setFileContent).catch(() => setFileContent('Error loading file'));
              return node;
            }
            if (node.expanded) {
              return { ...node, expanded: false };
            }
            if (node.children !== null) {
              return { ...node, expanded: true };
            }
            // Need to load
            const loading = { ...node, loading: true, expanded: true };
            fetchDir(path).then((entries) => {
              const sorted = entries.sort((a, b) => {
                if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
                return a.name.localeCompare(b.name);
              });
              setRootNodes((prev) =>
                prev
                  ? updateTree(prev, path, sorted.map((e) => ({
                      entry: e,
                      path: `${path}/${e.name}`,
                      children: e.type === 'directory' ? null : [],
                      loading: false,
                      expanded: false,
                    })))
                  : prev,
              );
            });
            return loading;
          }
          if (node.children && node.expanded) {
            return { ...node, children: updateNodes(node.children) };
          }
          return node;
        });

      setRootNodes((prev) => (prev ? updateNodes(prev) : prev));
    },
    [fetchDir, fetchFile],
  );

  if (!serverUrl) {
    return (
      <div className="flex h-full items-center justify-center">
        <span className="text-xs text-gray-400 dark:text-gray-500">Not connected</span>
      </div>
    );
  }

  if (!rootNodes) {
    return (
      <div className="flex h-full items-center justify-center">
        <button
          onClick={loadRoot}
          className="rounded bg-blue-100 px-3 py-1 text-xs font-medium text-blue-700 hover:bg-blue-200 dark:bg-blue-900/30 dark:text-blue-400 dark:hover:bg-blue-900/50"
        >
          Load workspace files
        </button>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2">
        <span className="text-xs text-red-500">{error}</span>
        <button
          onClick={loadRoot}
          className="text-xs text-blue-500 hover:underline"
        >
          Retry
        </button>
      </div>
    );
  }

  return (
    <div className="flex h-full gap-2">
      {/* File tree */}
      <div className="min-w-[200px] flex-1 overflow-auto">
        {rootNodes.map((node) => (
          <FileTreeNode
            key={node.path}
            node={node}
            depth={0}
            onToggle={toggleNode}
            modifiedFiles={modifiedFiles}
            selectedFile={selectedFile}
          />
        ))}
      </div>

      {/* File preview */}
      {selectedFile && fileContent !== null && (
        <div className="flex min-w-[300px] flex-[2] flex-col border-l border-gray-200 dark:border-gray-700">
          <div className="flex items-center justify-between border-b border-gray-200 px-2 py-1 dark:border-gray-700">
            <span className="truncate font-mono text-[10px] text-gray-600 dark:text-gray-400">
              {selectedFile}
            </span>
            <button
              onClick={() => { setSelectedFile(null); setFileContent(null); }}
              className="text-[10px] text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
            >
              close
            </button>
          </div>
          <pre className="flex-1 overflow-auto p-2 font-mono text-[10px] leading-relaxed text-gray-700 dark:text-gray-300">
            {fileContent}
          </pre>
        </div>
      )}
    </div>
  );
}

function FileTreeNode({
  node,
  depth,
  onToggle,
  modifiedFiles,
  selectedFile,
}: {
  node: TreeNode;
  depth: number;
  onToggle: (path: string) => void;
  modifiedFiles: Map<string, LiveFileChange>;
  selectedFile: string | null;
}) {
  const isDir = node.entry.type === 'directory';
  const modification = modifiedFiles.get(node.path);
  const isSelected = selectedFile === node.path;

  return (
    <div>
      <button
        onClick={() => onToggle(node.path)}
        className={[
          'flex w-full items-center gap-1 py-0.5 text-left hover:bg-gray-100 dark:hover:bg-gray-800',
          isSelected ? 'bg-blue-50 dark:bg-blue-900/20' : '',
        ].join(' ')}
        style={{ paddingLeft: `${depth * 16 + 4}px` }}
      >
        {/* Expand/collapse icon for directories */}
        {isDir ? (
          <span className="w-3 text-center text-[10px] text-gray-400">
            {node.loading ? '...' : node.expanded ? '▾' : '▸'}
          </span>
        ) : (
          <span className="w-3" />
        )}

        {/* File/dir icon */}
        <span className="text-[10px]">
          {isDir ? '📁' : getFileIcon(node.entry.name)}
        </span>

        {/* Name */}
        <span className="truncate text-[11px] text-gray-700 dark:text-gray-300">
          {node.entry.name}
        </span>

        {/* Modification indicator */}
        {modification && (
          <span
            className="ml-auto mr-1 h-1.5 w-1.5 rounded-full"
            style={{ backgroundColor: getAgentColor(modification.agentRole).bg }}
            title={`Modified by ${modification.agentRole}`}
          />
        )}
      </button>

      {/* Children */}
      {node.expanded && node.children && (
        <div>
          {node.children.map((child) => (
            <FileTreeNode
              key={child.path}
              node={child}
              depth={depth + 1}
              onToggle={onToggle}
              modifiedFiles={modifiedFiles}
              selectedFile={selectedFile}
            />
          ))}
          {node.children.length === 0 && !node.loading && (
            <div
              className="text-[10px] italic text-gray-400"
              style={{ paddingLeft: `${(depth + 1) * 16 + 4}px` }}
            >
              empty
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** Get a simple file icon based on extension. */
function getFileIcon(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase();
  switch (ext) {
    case 'java': return '☕';
    case 'ts': case 'tsx': return '🔷';
    case 'js': case 'jsx': return '🟨';
    case 'json': return '📋';
    case 'md': return '📝';
    case 'xml': return '📄';
    case 'yml': case 'yaml': return '⚙️';
    case 'py': return '🐍';
    case 'rs': return '🦀';
    case 'go': return '🔵';
    case 'css': case 'scss': return '🎨';
    case 'html': return '🌐';
    case 'sh': case 'bash': return '💻';
    case 'gradle': case 'kts': return '🐘';
    default: return '📄';
  }
}

/** Helper to update a specific node's children in the tree. */
function updateTree(nodes: TreeNode[], targetPath: string, children: TreeNode[]): TreeNode[] {
  return nodes.map((node) => {
    if (node.path === targetPath) {
      return { ...node, children, loading: false };
    }
    if (node.children && node.expanded) {
      return { ...node, children: updateTree(node.children, targetPath, children) };
    }
    return node;
  });
}
