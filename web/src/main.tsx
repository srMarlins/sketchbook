import { StrictMode } from 'react';
import React from 'react';
import { createRoot } from 'react-dom/client';
import './theme/tokens.css';
import { App } from './app/App';

if (import.meta.env.DEV) {
  void (async () => {
    const ReactDOM = await import('react-dom');
    const axe = (await import('@axe-core/react')).default;
    axe(React, ReactDOM, 1000);
  })();
}

const rootEl = document.getElementById('root');
if (!rootEl) throw new Error('#root not found');

createRoot(rootEl).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
