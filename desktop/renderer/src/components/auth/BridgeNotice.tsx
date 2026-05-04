export default function BridgeNotice() {
  if (window.cloudide) {
    return null;
  }

  return (
    <div className="bridge-notice">
      <strong>Browser preview only.</strong> Start the app in Electron with <code>cmd /c npm run dev</code>.
      <span>The Vite URL at http://localhost:5173 does not include the desktop preload bridge.</span>
    </div>
  );
}
