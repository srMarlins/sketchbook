import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { MarginStickyNote } from './MarginStickyNote';

describe('<MarginStickyNote />', () => {
  test('rotation is deterministic per id', () => {
    const a = render(<MarginStickyNote id="n-1" text="rename?" />);
    const rotA = (a.container.querySelector('button') as HTMLElement).style.transform;
    a.unmount();
    const b = render(<MarginStickyNote id="n-1" text="rename?" />);
    const rotB = (b.container.querySelector('button') as HTMLElement).style.transform;
    expect(rotB).toBe(rotA);
  });

  test('click fires onOpenSuggestion with id', async () => {
    const fn = vi.fn();
    render(<MarginStickyNote id="n-7" text="archive?" onOpenSuggestion={fn} />);
    await userEvent.click(screen.getByRole('button', { name: 'Open suggestion' }));
    expect(fn).toHaveBeenCalledWith('n-7');
  });

  test('uses font-display class', () => {
    render(<MarginStickyNote id="n-1" text="hi" />);
    const text = screen.getByText('hi');
    expect(text).toHaveClass('font-display');
  });
});
