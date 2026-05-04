import React from 'react';
import ReactDOM from 'react-dom/client';
import './monaco-workers';
import App from './App';
import '@vscode/codicons/dist/codicon.css';
import './styles/globals.css';

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
