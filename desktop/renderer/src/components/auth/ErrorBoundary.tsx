import React from 'react';

interface ErrorBoundaryState {
  error: Error | null;
}

export default class ErrorBoundary extends React.Component<React.PropsWithChildren, ErrorBoundaryState> {
  state: ErrorBoundaryState = {
    error: null,
  };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error('CloudIDE renderer error:', error, errorInfo);
  }

  render() {
    const { error } = this.state;
    if (error) {
      return (
        <div className="renderer-error">
          <h1>Renderer Error</h1>
          <p>{error.message}</p>
          <pre>{error.stack}</pre>
        </div>
      );
    }

    return this.props.children;
  }
}
