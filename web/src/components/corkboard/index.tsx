import type { Project } from '../../lib/types';
import { CorkboardPanel } from '../surface/CorkboardPanel';
import { Overview } from './Overview';
import { Tracks } from './Tracks';
import { Samples } from './Samples';
import { Plugins } from './Plugins';
import { History } from './History';

export interface ProjectCorkboardProps {
  project: Project | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  defaultTab?: string;
}

export function ProjectCorkboard({ project, open, onOpenChange, defaultTab }: ProjectCorkboardProps) {
  if (!project) {
    return (
      <CorkboardPanel
        open={open}
        onOpenChange={onOpenChange}
        title="—"
        tabs={[{ id: 'empty', label: 'Overview', content: <p>no project selected</p> }]}
      />
    );
  }

  return (
    <CorkboardPanel
      open={open}
      onOpenChange={onOpenChange}
      title={project.name}
      defaultTab={defaultTab ?? 'overview'}
      tabs={[
        { id: 'overview', label: 'Overview', content: <Overview project={project} /> },
        { id: 'tracks', label: 'Tracks', content: <Tracks project={project} /> },
        { id: 'samples', label: 'Samples', content: <Samples project={project} /> },
        { id: 'plugins', label: 'Plugins', content: <Plugins project={project} /> },
        { id: 'history', label: 'History', content: <History projectId={project.id} /> },
      ]}
    />
  );
}
