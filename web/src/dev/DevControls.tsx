import { useId } from 'react';
import type { DevControlOption, DevControlValues, DevEntry } from './types';

interface Props {
  entry: DevEntry;
  values: DevControlValues;
  onChange: (next: DevControlValues) => void;
}

export function DevControls({ entry, values, onChange }: Props) {
  if (!entry.controls) return null;
  return (
    <div className="flex flex-wrap gap-3 px-3 py-2 bg-surface-strip/60 rounded text-sm">
      {Object.entries(entry.controls).map(([key, opt]) => (
        <Field
          key={key}
          opt={opt}
          value={values[key] ?? opt.defaultValue}
          onChange={(v) => onChange({ ...values, [key]: v })}
        />
      ))}
    </div>
  );
}

function Field({
  opt,
  value,
  onChange,
}: {
  opt: DevControlOption;
  value: unknown;
  onChange: (v: unknown) => void;
}) {
  const id = useId();
  return (
    <label htmlFor={id} className="flex items-center gap-2">
      <span className="text-ink-muted">{opt.label}</span>
      {opt.type === 'select' && opt.options ? (
        <select
          id={id}
          value={String(value)}
          onChange={(e) => onChange(e.target.value)}
          className="rounded border border-rule-line bg-surface-page px-2 py-1"
        >
          {opt.options.map((o) => (
            <option key={String(o)} value={String(o)}>
              {String(o)}
            </option>
          ))}
        </select>
      ) : opt.type === 'toggle' ? (
        <input
          id={id}
          type="checkbox"
          checked={Boolean(value)}
          onChange={(e) => onChange(e.target.checked)}
        />
      ) : opt.type === 'number' ? (
        <input
          id={id}
          type="number"
          value={Number(value)}
          onChange={(e) => onChange(Number(e.target.value))}
          className="rounded border border-rule-line bg-surface-page px-2 py-1 w-20"
        />
      ) : (
        <input
          id={id}
          type="text"
          value={String(value)}
          onChange={(e) => onChange(e.target.value)}
          className="rounded border border-rule-line bg-surface-page px-2 py-1"
        />
      )}
    </label>
  );
}
