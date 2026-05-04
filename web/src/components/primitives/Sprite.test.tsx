import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { Sprite } from './Sprite';

describe('<Sprite />', () => {
  test('renders an img referencing the doodles asset path', () => {
    const { container } = render(<Sprite name="metronome" />);
    const img = container.querySelector('img');
    expect(img).not.toBeNull();
    expect(img!.getAttribute('src')).toBe('/raw/icons/doodles/metronome.png');
    expect(img!.getAttribute('aria-hidden')).toBe('true');
  });

  test('field sprites resolve to /raw/icons/field', () => {
    const { container } = render(<Sprite name="bpm" />);
    expect(container.querySelector('img')!.getAttribute('src')).toBe('/raw/icons/field/bpm.png');
  });

  test('with label, exposes AccessibleIcon', () => {
    render(<Sprite name="checkmark" label="Approve" />);
    expect(screen.getByText('Approve')).toBeInTheDocument(); // visually-hidden span
  });
});
