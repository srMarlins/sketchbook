import { useProject } from '../../app/queries';
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
  const detail = useProject(projectId ?? 0);

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
