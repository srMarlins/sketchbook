import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { CorkboardPanel } from './CorkboardPanel';

describe('<CorkboardPanel />', () => {
  const tabs = [
    { id: 'overview', label: 'Overview', content: <div>OV</div> },
    { id: 'tracks', label: 'Tracks', content: <div>TR</div> },
  ];

  test('renders tabs and title when open', () => {
    render(
      <CorkboardPanel open onOpenChange={() => undefined} title="lazy ridge" tabs={tabs} />,
    );
    expect(screen.getByText('lazy ridge')).toBeInTheDocument();
    expect(screen.getByText('Overview')).toBeInTheDocument();
    expect(screen.getByText('Tracks')).toBeInTheDocument();
  });

  test('Close button fires onOpenChange(false)', async () => {
    const fn = vi.fn();
    render(<CorkboardPanel open onOpenChange={fn} title="x" tabs={tabs} />);
    await userEvent.click(screen.getByRole('button', { name: /close panel/i }));
    expect(fn).toHaveBeenCalledWith(false);
  });

  test('Esc closes via Radix Dialog', async () => {
    const fn = vi.fn();
    render(<CorkboardPanel open onOpenChange={fn} title="x" tabs={tabs} />);
    await userEvent.keyboard('{Escape}');
    expect(fn).toHaveBeenCalledWith(false);
  });
});
