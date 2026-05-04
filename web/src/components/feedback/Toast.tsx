import * as RadixToast from '@radix-ui/react-toast';
import clsx from 'clsx';
import type { ReactNode } from 'react';

export type ToastTone = 'info' | 'success' | 'error';

const TONE_CLS: Record<ToastTone, string> = {
  info: 'bg-surface-panel border-rule-line text-ink-primary',
  success: 'bg-pin-green/15 border-pin-green/40 text-ink-primary',
  error: 'bg-accent-action/15 border-accent-action/40 text-ink-primary',
};

export interface ToastProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: ReactNode;
  tone?: ToastTone;
  duration?: number;
}

export function Toast({
  open,
  onOpenChange,
  title,
  description,
  tone = 'info',
  duration = 4000,
}: ToastProps) {
  return (
    <RadixToast.Root
      open={open}
      onOpenChange={onOpenChange}
      duration={duration}
      className={clsx(
        'rounded-sm border px-3 py-2 shadow-lift',
        'data-[state=open]:animate-in data-[state=closed]:animate-out',
        TONE_CLS[tone],
      )}
    >
      <RadixToast.Title className="font-display text-base">{title}</RadixToast.Title>
      {description ? (
        <RadixToast.Description className="font-sans text-sm text-ink-secondary">
          {description}
        </RadixToast.Description>
      ) : null}
    </RadixToast.Root>
  );
}

export const ToastProvider = RadixToast.Provider;
export const ToastViewport = (props: RadixToast.ToastViewportProps) => (
  <RadixToast.Viewport
    {...props}
    className={clsx(
      'fixed bottom-4 right-4 flex w-80 flex-col gap-2 z-toast outline-none',
      props.className,
    )}
  />
);
