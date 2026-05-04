import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { EmptyState } from './EmptyState';
import { ErrorState } from './ErrorState';

describe('<EmptyState />', () => {
  test('renders title and body', () => {
    render(<EmptyState title="nothing here" body="add a sketch" />);
    expect(screen.getByText('nothing here')).toBeInTheDocument();
    expect(screen.getByText('add a sketch')).toBeInTheDocument();
  });
});

describe('<ErrorState />', () => {
  test('alert role, fires retry', async () => {
    const fn = vi.fn();
    render(<ErrorState body="500 from /api/projects" onRetry={fn} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'try again' }));
    expect(fn).toHaveBeenCalled();
  });
});
