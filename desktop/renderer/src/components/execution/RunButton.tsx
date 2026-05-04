import { useExecutionStore } from '../../store/executionStore';
import Codicon from '../common/Codicon';

export default function RunButton() {
  const { isRunning, runCurrentFile, killRun } = useExecutionStore();

  return (
    <button
      className="run-button"
      onClick={() => {
        if (isRunning) {
          void killRun();
        } else {
          void runCurrentFile();
          window.dispatchEvent(new CustomEvent('cloudide:fileRan'));
        }
      }}
      type="button"
      title={isRunning ? 'Stop' : 'Run File'}
    >
      <Codicon name={isRunning ? 'debug-stop' : 'play'} size={14} />
      {isRunning ? 'Stop' : 'Run'}
    </button>
  );
}
