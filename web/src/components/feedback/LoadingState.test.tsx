import { render } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { LoadingState } from './LoadingState';

describe('<LoadingState />', () => {
  test('animation duration capped at 3s', () => {
    const { container } = render(<LoadingState />);
    const styled = container.querySelector('[style*="animation-duration"]') as HTMLElement | null;
    expect(styled).not.toBeNull();
    const style = styled!.getAttribute('style') ?? '';
    expect(style).toMatch(/animation-duration:\s*3s/);
  });

  test('renders provided label', () => {
    const { getByText } = render(<LoadingState label="loading sketches…" />);
    expect(getByText('loading sketches…')).toBeInTheDocument();
  });
});
