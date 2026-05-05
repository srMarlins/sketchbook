import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { RepairPanel } from './RepairPanel';

const propsClean = { macImport: null, missingSamples: [], onPropose: vi.fn() };

describe('RepairPanel', () => {
  it('renders nothing when project is clean', () => {
    const { container } = render(<RepairPanel {...propsClean} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows mac-paths chip and missing-sample list', () => {
    const onPropose = vi.fn();
    render(
      <RepairPanel
        macImport={{ projectId: 1, macPathsCount: 12, projectInfoMissing: true }}
        missingSamples={[
          {
            missingPath: 'k.wav',
            autoMatch: { path: '/lib/k.wav', filename: 'k.wav', sizeBytes: 1 },
            candidates: [],
          },
          {
            missingPath: 's.wav',
            autoMatch: null,
            candidates: [
              { path: '/lib/s1.wav', filename: 's.wav', sizeBytes: 2 },
              { path: '/lib/s2.wav', filename: 's.wav', sizeBytes: 3 },
            ],
          },
        ]}
        onPropose={onPropose}
      />,
    );
    expect(screen.getByText(/12 mac paths/i)).toBeInTheDocument();
    expect(screen.getByText('k.wav')).toBeInTheDocument();
    expect(screen.getByText('s.wav')).toBeInTheDocument();
  });

  it('propose-button calls onPropose with auto-matches plus picks', () => {
    const onPropose = vi.fn();
    render(
      <RepairPanel
        macImport={{ projectId: 1, macPathsCount: 12, projectInfoMissing: true }}
        missingSamples={[
          {
            missingPath: 'k.wav',
            autoMatch: { path: '/lib/k.wav', filename: 'k.wav', sizeBytes: 1 },
            candidates: [],
          },
          {
            missingPath: 's.wav',
            autoMatch: null,
            candidates: [
              { path: '/lib/s1.wav', filename: 's.wav', sizeBytes: 2 },
              { path: '/lib/s2.wav', filename: 's.wav', sizeBytes: 3 },
            ],
          },
        ]}
        onPropose={onPropose}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /pick/i }));
    fireEvent.click(screen.getByRole('button', { name: '/lib/s2.wav' }));
    fireEvent.click(screen.getByRole('button', { name: /propose repair/i }));
    expect(onPropose).toHaveBeenCalledWith({
      macImport: true,
      relinks: { 'k.wav': '/lib/k.wav', 's.wav': '/lib/s2.wav' },
    });
  });
});
