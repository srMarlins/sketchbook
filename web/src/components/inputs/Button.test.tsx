import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { Button } from './Button';

describe('<Button />', () => {
  test('fires onClick', async () => {
    const fn = vi.fn();
    render(<Button onClick={fn}>Save</Button>);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(fn).toHaveBeenCalledTimes(1);
  });

  test('disabled prevents clicks', async () => {
    const fn = vi.fn();
    render(
      <Button disabled onClick={fn}>
        Save
      </Button>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(fn).not.toHaveBeenCalled();
  });

  test('renders all three variants without throwing', () => {
    render(
      <>
        <Button variant="primary">a</Button>
        <Button variant="secondary">b</Button>
        <Button variant="ghost">c</Button>
      </>,
    );
    expect(screen.getAllByRole('button')).toHaveLength(3);
  });

  test('uses font-display class', () => {
    render(<Button>Save</Button>);
    expect(screen.getByRole('button')).toHaveClass('font-display');
  });
});
