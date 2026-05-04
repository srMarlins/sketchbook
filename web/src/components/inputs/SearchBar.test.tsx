import { render, screen, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, test } from 'vitest';
import { SearchBar, useSearchStore } from './SearchBar';
import { __resetKeyboardRegistry } from '../../hooks/useKeyboard';

describe('<SearchBar />', () => {
  beforeEach(() => {
    __resetKeyboardRegistry();
    act(() => {
      useSearchStore.getState().clear();
    });
  });

  test('typing updates Zustand store', async () => {
    render(<SearchBar />);
    const input = screen.getByRole('searchbox');
    await userEvent.type(input, 'lazy');
    expect(useSearchStore.getState().query).toBe('lazy');
  });

  test('Cmd-K focuses input', async () => {
    render(<SearchBar />);
    const input = screen.getByRole('searchbox');
    expect(document.activeElement).not.toBe(input);
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', metaKey: true }));
    expect(document.activeElement).toBe(input);
  });

  test('Esc clears query and blurs input when focused', async () => {
    render(<SearchBar />);
    const input = screen.getByRole('searchbox') as HTMLInputElement;
    await userEvent.type(input, 'foo');
    expect(useSearchStore.getState().query).toBe('foo');
    input.focus();
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(useSearchStore.getState().query).toBe('');
  });
});
