import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { NotebookSpine } from './NotebookSpine';
import { Shelf } from './Shelf';

describe('<Shelf /> + <NotebookSpine />', () => {
  test('shelf has a labeled region and renders children', () => {
    render(
      <Shelf>
        <NotebookSpine id="2024" title="2024" count={42} />
      </Shelf>,
    );
    expect(screen.getByRole('region', { name: 'Shelf' })).toBeInTheDocument();
    expect(screen.getByText('2024')).toBeInTheDocument();
  });

  test('spine click fires onOpen with id', async () => {
    const fn = vi.fn();
    render(<NotebookSpine id="claude" title="Claude" onOpen={fn} />);
    await userEvent.click(screen.getByRole('button'));
    expect(fn).toHaveBeenCalledWith('claude');
  });

  test('kind data attribute reflects prop', () => {
    const { container } = render(<NotebookSpine id="x" title="Kraft" kind="kraft" />);
    expect(container.querySelector('[data-kind="kraft"]')).not.toBeNull();
  });
});
