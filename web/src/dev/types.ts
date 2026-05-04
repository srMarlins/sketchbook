import type { ReactNode } from 'react';

export type DevGroup = 'primitives' | 'inputs' | 'data' | 'feedback' | 'surface';

export interface DevControlOption<T = unknown> {
  type: 'select' | 'toggle' | 'text' | 'number';
  label: string;
  defaultValue: T;
  options?: ReadonlyArray<T>;
}

export type DevControlValues = Record<string, unknown>;

export interface DevEntry {
  id: string;
  group: DevGroup;
  label: string;
  /** Pure render of the component, given the current control values. */
  render: (controls: DevControlValues) => ReactNode;
  controls?: Record<string, DevControlOption>;
}
