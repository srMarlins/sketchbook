import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { FilterChip } from './FilterChip';

describe('<FilterChip />', () => {
  test('shows label and value', () => {
    render(<FilterChip label="tempo" value="120" />);
    expect(screen.getByText('tempo')).toBeInTheDocument();
    expect(screen.getByText('120')).toBeInTheDocument();
  });

  test('dismiss button fires onDismiss', async () => {
    const fn = vi.fn();
    render(<FilterChip label="tempo" onDismiss={fn} />);
    await userEvent.click(screen.getByRole('button', { name: /remove tempo filter/i }));
    expect(fn).toHaveBeenCalledTimes(1);
  });

  test('no dismiss button when onDismiss missing', () => {
    render(<FilterChip label="tempo" />);
    expect(screen.queryByRole('button')).toBeNull();
  });
});
