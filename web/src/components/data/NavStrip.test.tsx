import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { NavStrip } from './NavStrip';

describe('<NavStrip />', () => {
  test('click fires handler', async () => {
    const fn = vi.fn();
    render(<NavStrip label="Projects" onClick={fn} />);
    await userEvent.click(screen.getByRole('button', { name: 'Projects' }));
    expect(fn).toHaveBeenCalledTimes(1);
  });

  test('active gets aria-current=page', () => {
    render(<NavStrip label="Projects" active />);
    expect(screen.getByRole('button', { name: 'Projects' })).toHaveAttribute('aria-current', 'page');
  });

  test('badge renders when provided', () => {
    render(<NavStrip label="Proposals" badge={3} />);
    expect(screen.getByText('3')).toBeInTheDocument();
  });
});
