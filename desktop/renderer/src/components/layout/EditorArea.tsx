import MonacoEditor from '../editor/MonacoEditor';
import EditorTabs from '../editor/EditorTabs';
import RunButton from '../execution/RunButton';

export default function EditorArea() {
  return (
    <section className="editor-area">
      <div className="editor-area__toolbar">
        <EditorTabs />
        <RunButton />
      </div>
      <MonacoEditor />
    </section>
  );
}
