import { AppRouter } from './router';
import { SidecarHealth } from './SidecarHealth';

export function App() {
  return (
    <>
      <AppRouter />
      <SidecarHealth />
    </>
  );
}
