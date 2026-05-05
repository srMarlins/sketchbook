import { useEffect, useState } from 'react';

/**
 * Listens for `sidecar-died` events emitted by the Tauri shell when the
 * Python backend exits unexpectedly (crash, OOM, killed). Shows a blocking
 * full-screen modal so the user knows the app is in a degraded state and
 * has to restart.
 *
 * No-op when not running inside Tauri (i.e. the regular browser dev mode).
 * The Tauri runtime injects `window.__TAURI__` and its event API; we feature-
 * detect rather than import @tauri-apps/api directly so the same bundle works
 * in both browser and packaged contexts.
 */

type TauriEvent = {
  payload: { stage: string; detail: string };
};

type TauriEventApi = {
  listen: (
    event: string,
    handler: (event: TauriEvent) => void,
  ) => Promise<() => void>;
};

function getTauriEvents(): TauriEventApi | null {
  const w = window as unknown as { __TAURI__?: { event?: TauriEventApi } };
  return w.__TAURI__?.event ?? null;
}

export function SidecarHealth() {
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const api = getTauriEvents();
    if (!api) return;
    let unsub: (() => void) | undefined;
    let cancelled = false;
    api
      .listen('sidecar-died', (event) => {
        if (cancelled) return;
        setError(event.payload.detail || 'backend exited unexpectedly');
      })
      .then((fn) => {
        if (cancelled) fn();
        else unsub = fn;
      })
      .catch(() => {
        /* ignore — older Tauri without event API */
      });
    return () => {
      cancelled = true;
      if (unsub) unsub();
    };
  }, []);

  if (!error) return null;

  return (
    <div
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="sidecar-died-title"
      className="fixed inset-0 z-[10000] flex items-center justify-center bg-ink-primary/40 backdrop-blur-sm p-6"
    >
      <div className="max-w-md rounded-card border border-rule-line bg-surface-card shadow-card p-6 space-y-4">
        <h2
          id="sidecar-died-title"
          className="text-lg font-semibold text-ink-primary"
        >
          Backend disconnected
        </h2>
        <p className="text-sm text-ink-secondary">
          The audio backend exited unexpectedly. Your data is safe — every
          completed action was journaled before any crash. Restart the app to
          reconnect.
        </p>
        <pre className="text-[11px] font-mono whitespace-pre-wrap break-words text-ink-muted bg-surface-sunken rounded-input p-3 max-h-32 overflow-auto">
          {error}
        </pre>
      </div>
    </div>
  );
}
