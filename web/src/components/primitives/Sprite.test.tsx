import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { Sprite } from './Sprite';

describe('<Sprite />', () => {
  test('renders a span masking the doodles asset path', () => {
    const { container } = render(<Sprite name="metronome" />);
    const span = container.querySelector('span');
    expect(span).not.toBeNull();
    expect(span!.getAttribute('style')).toContain('/raw/icons/doodles/metronome.png');
    expect(span!.getAttribute('aria-hidden')).toBe('true');
  });

  test('field sprites resolve to /raw/icons/field', () => {
    const { container } = render(<Sprite name="bpm" />);
    expect(container.querySelector('span')!.getAttribute('style')).toContain(
      '/raw/icons/field/bpm.png',
    );
  });

  test('with label, exposes AccessibleIcon', () => {
    render(<Sprite name="checkmark" label="Approve" />);
    expect(screen.getByText('Approve')).toBeInTheDocument(); // visually-hidden span
  });
});
