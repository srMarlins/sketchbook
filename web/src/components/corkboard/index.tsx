import { useProject, useOpenProject } from '../../app/queries';
import { CorkboardPanel } from '../surface/CorkboardPanel';
import { LoadingState } from '../feedback/LoadingState';
import { ErrorState } from '../feedback/ErrorState';
import { Overview } from './Overview';
import { Tracks } from './Tracks';
import { Samples } from './Samples';
import { Plugins } from './Plugins';
import { History } from './History';

export interface ProjectCorkboardProps {
  projectId: number | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  defaultTab?: string;
}

export function ProjectCorkboard({
  projectId,
  open,
  onOpenChange,
  defaultTab,
}: ProjectCorkboardProps) {
  const detail = useProject(projectId);
  const launch = useOpenProject();

  if (projectId == null) {
    return (
      <CorkboardPanel
        open={open}
        onOpenChange={onOpenChange}
        title="—"
        tabs={[{ id: 'empty', label: 'Overview', content: <p>no project selected</p> }]}
      />
    );
  }

  const project = detail.data;

  return (
    <CorkboardPanel
      open={open}
      onOpenChange={onOpenChange}
      title={project?.name ?? 'loading…'}
      defaultTab={defaultTab ?? 'overview'}
      headerActions={
        project ? (
          <button
            type="button"
            onClick={() => launch.mutate(projectId)}
            disabled={launch.isPending}
            className="shrink-0 inline-flex items-center gap-1.5 px-2.5 py-1 rounded-input text-sm border border-accent/40 bg-accent/10 text-accent hover:bg-accent/20 disabled:opacity-50 transition-colors"
            title={launch.isError ? String(launch.error) : `Open ${project.name} in Ableton`}
          >
            <span aria-hidden>↗</span>
            <span>open in ableton</span>
          </button>
        ) : null
      }
      tabs={[
        {
          id: 'overview',
          label: 'Overview',
          content: detail.isLoading ? (
            <LoadingState />
          ) : detail.isError ? (
            <ErrorState body={String(detail.error)} onRetry={() => detail.refetch()} />
          ) : project ? (
            <Overview project={project} />
          ) : null,
        },
        {
          id: 'tracks',
          label: 'Tracks',
          content: project ? <Tracks project={project} /> : null,
        },
        {
          id: 'samples',
          label: 'Samples',
          content: project ? <Samples project={project} /> : null,
        },
        {
          id: 'plugins',
          label: 'Plugins',
          content: project ? <Plugins project={project} /> : null,
        },
        { id: 'history', label: 'History', content: <History projectId={projectId} /> },
      ]}
    />
  );
}
