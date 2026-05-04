import { render, screen } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import { Toast, ToastProvider, ToastViewport } from './Toast';

describe('<Toast />', () => {
  test('renders title when open', () => {
    render(
      <ToastProvider>
        <Toast open onOpenChange={vi.fn()} title="Saved" description="2 changes" />
        <ToastViewport />
      </ToastProvider>,
    );
    expect(screen.getByText('Saved')).toBeInTheDocument();
    expect(screen.getByText('2 changes')).toBeInTheDocument();
  });
});
