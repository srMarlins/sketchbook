import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test } from 'vitest';
import { TextInput } from './TextInput';

describe('<TextInput />', () => {
  test('renders label and accepts typing', async () => {
    render(<TextInput label="Project" defaultValue="" />);
    const input = screen.getByLabelText('Project');
    await userEvent.type(input, 'lazy');
    expect((input as HTMLInputElement).value).toBe('lazy');
  });

  test('hint links via aria-describedby', () => {
    render(<TextInput label="Project" hint="must be unique" />);
    const input = screen.getByLabelText('Project') as HTMLInputElement;
    const id = input.getAttribute('aria-describedby');
    expect(id).toBeTruthy();
    expect(document.getElementById(id!)?.textContent).toBe('must be unique');
  });

  test('invalid sets aria-invalid', () => {
    render(<TextInput label="Project" invalid />);
    expect(screen.getByLabelText('Project')).toHaveAttribute('aria-invalid', 'true');
  });
});
