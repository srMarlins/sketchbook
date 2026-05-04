import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { Sidebar, type SidebarItem } from './Sidebar';

const items: SidebarItem[] = [
  { id: 'home', label: 'Home', icon: 'house' },
  { id: 'projects', label: 'Projects', icon: 'folder' },
  { id: 'proposals', label: 'Proposals', icon: 'paper-airplane', badge: 3 },
];

describe('<Sidebar />', () => {
  test('renders all 7-ish nav strips', () => {
    render(<Sidebar items={items} activeId="projects" />);
    expect(screen.getAllByRole('button')).toHaveLength(items.length);
  });

  test('Enter activates current item', async () => {
    const fn = vi.fn();
    render(<Sidebar items={items} activeId="projects" onActivate={fn} />);
    const projects = screen.getByRole('button', { name: 'Projects' });
    projects.focus();
    await userEvent.keyboard('{Enter}');
    expect(fn).toHaveBeenCalledWith('projects');
  });

  test('ArrowDown moves focus to next', async () => {
    render(<Sidebar items={items} activeId="home" />);
    await userEvent.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(screen.getByRole('button', { name: /Projects/i }));
  });
});
