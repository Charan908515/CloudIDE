import { useEffect } from 'react';
import { useExecutionStore } from '../../store/executionStore';

export default function OutputPanel() {
  const bridge = window.cloudide;
  const { output, appendOutput, finishRun, isRunning } = useExecutionStore();

  useEffect(() => {
    if (!bridge?.exec) {
      return;
    }

    const removeOutput = bridge.exec.onOutput((payload) => {
      appendOutput(payload.data);
    });
    const removeDone = bridge.exec.onDone(() => {
      finishRun();
    });

    return () => {
      removeOutput();
      removeDone();
    };
  }, [appendOutput, bridge, finishRun]);

  return (
    <div className="output-panel">
      <div className="output-panel__header">
        <span>{isRunning ? 'Running...' : 'Ready'}</span>
      </div>
      <pre className="output-panel__body">
        {!bridge?.exec ? 'Output is available only in the Electron desktop window.' : output || 'Run the active file to stream stdout and stderr here.'}
      </pre>
    </div>
  );
}
