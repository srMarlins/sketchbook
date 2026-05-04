import { useMemo, useState } from 'react';
import { registry } from './registry';
import { DevControls } from './DevControls';
import type { DevControlValues, DevEntry, DevGroup } from './types';

const GROUP_ORDER: DevGroup[] = ['primitives', 'inputs', 'data', 'feedback', 'surface'];

type ThemeMode = 'light' | 'dark' | 'compare';

export function DevShell() {
  const [active, setActive] = useState<string>(registry[0]?.id ?? '');
  const [theme, setTheme] = useState<ThemeMode>('compare');
  const [reducedMotion, setReducedMotion] = useState(false);
  const [width, setWidth] = useState<'narrow' | 'wide'>('wide');
  const [valuesByEntry, setValuesByEntry] = useState<Record<string, DevControlValues>>({});

  const entry = registry.find((e) => e.id === active);
  const groups = useMemo(() => {
    const byGroup = new Map<DevGroup, DevEntry[]>();
    for (const e of registry) {
      const arr = byGroup.get(e.group) ?? [];
      arr.push(e);
      byGroup.set(e.group, arr);
    }
    return GROUP_ORDER.flatMap((g) => (byGroup.has(g) ? [{ group: g, items: byGroup.get(g)! }] : []));
  }, []);

  const values: DevControlValues = useMemo(() => {
    if (!entry) return {};
    const stored = valuesByEntry[entry.id] ?? {};
    const defaults: DevControlValues = {};
    if (entry.controls) {
      for (const [k, opt] of Object.entries(entry.controls)) defaults[k] = opt.defaultValue;
    }
    return { ...defaults, ...stored };
  }, [entry, valuesByEntry]);

  return (
    <div className="min-h-screen flex bg-surface-page text-ink-primary">
      {reducedMotion ? <ReducedMotionStyles /> : null}
      <h1 className="sr-only">Sketchbook component viewer</h1>
      <aside className="w-56 shrink-0 border-r border-rule-line p-3 sticky top-0 h-screen overflow-y-auto">
        <div className="text-xs uppercase tracking-wide text-ink-muted mb-2">/_dev</div>
        <nav className="space-y-3">
          {groups.map(({ group, items }) => (
            <div key={group}>
              <div className="text-xs uppercase tracking-wide text-ink-muted mb-1">{group}</div>
              <ul className="space-y-1">
                {items.map((e) => (
                  <li key={e.id}>
                    <button
                      type="button"
                      onClick={() => setActive(e.id)}
                      className={
                        'w-full text-left px-2 py-1 rounded text-sm hover:bg-surface-strip ' +
                        (e.id === active ? 'bg-surface-strip font-semibold' : '')
                      }
                    >
                      {e.label}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>
      </aside>

      <main className="flex-1 p-6 space-y-4">
        <header className="flex flex-wrap gap-3 items-center text-sm">
          <span className="font-mono text-ink-muted">component viewer</span>
          <ToggleGroup
            label="theme"
            value={theme}
            options={['light', 'dark', 'compare']}
            onChange={(v) => setTheme(v as ThemeMode)}
          />
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={reducedMotion}
              onChange={(e) => setReducedMotion(e.target.checked)}
            />
            <span>reduced-motion</span>
          </label>
          <ToggleGroup
            label="width"
            value={width}
            options={['narrow', 'wide']}
            onChange={(v) => setWidth(v as 'narrow' | 'wide')}
          />
        </header>

        {entry ? (
          <section className="space-y-4">
            <h2 className="font-display text-3xl">{entry.label}</h2>
            <DevControls
              entry={entry}
              values={values}
              onChange={(next) => setValuesByEntry((prev) => ({ ...prev, [entry.id]: next }))}
            />
            <ThemeStage mode={theme} narrow={width === 'narrow'}>
              {entry.render(values)}
            </ThemeStage>
          </section>
        ) : (
          <p className="text-ink-muted">no components registered</p>
        )}
      </main>
    </div>
  );
}

function ToggleGroup({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: string;
  options: readonly string[];
  onChange: (v: string) => void;
}) {
  return (
    <div className="flex items-center gap-1">
      <span className="text-ink-muted">{label}</span>
      {options.map((o) => (
        <button
          key={o}
          type="button"
          onClick={() => onChange(o)}
          className={
            'px-2 py-0.5 rounded text-xs border ' +
            (o === value
              ? 'border-ink-primary bg-ink-primary text-surface-page'
              : 'border-rule-line text-ink-muted hover:text-ink-primary')
          }
        >
          {o}
        </button>
      ))}
    </div>
  );
}

function ThemeStage({
  mode,
  narrow,
  children,
}: {
  mode: ThemeMode;
  narrow: boolean;
  children: React.ReactNode;
}) {
  const widthCls = narrow ? 'max-w-md' : 'max-w-3xl';
  if (mode === 'compare') {
    return (
      <div className="grid gap-4 md:grid-cols-2">
        <Frame label="light" theme="light" widthCls={widthCls}>
          {children}
        </Frame>
        <Frame label="dark" theme="dark" widthCls={widthCls}>
          {children}
        </Frame>
      </div>
    );
  }
  return (
    <Frame label={mode} theme={mode} widthCls={widthCls}>
      {children}
    </Frame>
  );
}

function Frame({
  label,
  theme,
  widthCls,
  children,
}: {
  label: string;
  theme: 'light' | 'dark';
  widthCls: string;
  children: React.ReactNode;
}) {
  return (
    <div data-theme={theme} className={`bg-surface-page text-ink-primary p-6 rounded shadow-page ${widthCls}`}>
      <div className="text-xs uppercase tracking-wide text-ink-muted mb-3">{label}</div>
      <div>{children}</div>
    </div>
  );
}

function ReducedMotionStyles() {
  return (
    <style>{`
      *,*::before,*::after {
        animation: none !important;
        transition: none !important;
      }
    `}</style>
  );
}
