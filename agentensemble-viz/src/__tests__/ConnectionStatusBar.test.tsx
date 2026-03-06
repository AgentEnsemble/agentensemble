import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ConnectionStatusBar from '../components/shared/ConnectionStatusBar.js';

describe('ConnectionStatusBar', () => {
  it('renders "Connected" label for connected status', () => {
    render(<ConnectionStatusBar status="connected" serverUrl={null} />);
    expect(screen.getByTestId('connection-status-label')).toHaveTextContent('Connected');
  });

  it('renders "Connecting..." label for connecting status', () => {
    render(<ConnectionStatusBar status="connecting" serverUrl={null} />);
    expect(screen.getByTestId('connection-status-label')).toHaveTextContent('Connecting...');
  });

  it('renders "Disconnected" label for disconnected status', () => {
    render(<ConnectionStatusBar status="disconnected" serverUrl={null} />);
    expect(screen.getByTestId('connection-status-label')).toHaveTextContent('Disconnected');
  });

  it('renders "Connection error" label for error status', () => {
    render(<ConnectionStatusBar status="error" serverUrl={null} />);
    expect(screen.getByTestId('connection-status-label')).toHaveTextContent('Connection error');
  });

  it('shows green dot for connected status', () => {
    render(<ConnectionStatusBar status="connected" serverUrl={null} />);
    const dot = screen.getByTestId('connection-status-dot');
    expect(dot.className).toContain('bg-green-500');
  });

  it('shows amber dot for connecting status', () => {
    render(<ConnectionStatusBar status="connecting" serverUrl={null} />);
    const dot = screen.getByTestId('connection-status-dot');
    expect(dot.className).toContain('bg-amber-400');
  });

  it('shows ae-pulse animation class on dot for connecting status', () => {
    render(<ConnectionStatusBar status="connecting" serverUrl={null} />);
    const dot = screen.getByTestId('connection-status-dot');
    expect(dot.className).toContain('ae-pulse');
  });

  it('shows red dot for disconnected status', () => {
    render(<ConnectionStatusBar status="disconnected" serverUrl={null} />);
    const dot = screen.getByTestId('connection-status-dot');
    expect(dot.className).toContain('bg-red-500');
  });

  it('shows red dot for error status', () => {
    render(<ConnectionStatusBar status="error" serverUrl={null} />);
    const dot = screen.getByTestId('connection-status-dot');
    expect(dot.className).toContain('bg-red-500');
  });

  it('does not show pulse animation class for connected status', () => {
    render(<ConnectionStatusBar status="connected" serverUrl={null} />);
    const dot = screen.getByTestId('connection-status-dot');
    expect(dot.className).not.toContain('ae-pulse');
  });

  it('does not show pulse animation class for disconnected status', () => {
    render(<ConnectionStatusBar status="disconnected" serverUrl={null} />);
    const dot = screen.getByTestId('connection-status-dot');
    expect(dot.className).not.toContain('ae-pulse');
  });

  it('displays the server URL when provided', () => {
    render(
      <ConnectionStatusBar status="connected" serverUrl="ws://localhost:7329/ws" />,
    );
    expect(screen.getByTestId('connection-status-url')).toHaveTextContent(
      'ws://localhost:7329/ws',
    );
  });

  it('does not render URL element when serverUrl is null', () => {
    render(<ConnectionStatusBar status="connected" serverUrl={null} />);
    expect(screen.queryByTestId('connection-status-url')).toBeNull();
  });

  it('sets data-status attribute to the current status value', () => {
    const { rerender } = render(<ConnectionStatusBar status="connected" serverUrl={null} />);
    expect(screen.getByTestId('connection-status-bar')).toHaveAttribute(
      'data-status',
      'connected',
    );

    rerender(<ConnectionStatusBar status="disconnected" serverUrl={null} />);
    expect(screen.getByTestId('connection-status-bar')).toHaveAttribute(
      'data-status',
      'disconnected',
    );
  });

  it('has role=status for accessibility', () => {
    render(<ConnectionStatusBar status="connected" serverUrl={null} />);
    expect(screen.getByRole('status')).toBeInTheDocument();
  });
});
