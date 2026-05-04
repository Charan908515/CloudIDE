import { useEffect, useRef, useState } from 'react';

interface SplitterProps {
  orientation: 'vertical' | 'horizontal';
  onResize(deltaPixels: number): void;
  className?: string;
}

export default function Splitter({ orientation, onResize, className = '' }: SplitterProps) {
  const startRef = useRef<number>(0);
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    if (!dragging) return;

    const handleMove = (event: PointerEvent) => {
      const current = orientation === 'vertical' ? event.clientX : event.clientY;
      const delta = current - startRef.current;
      if (delta !== 0) {
        startRef.current = current;
        onResize(delta);
      }
    };
    const stop = () => setDragging(false);

    window.addEventListener('pointermove', handleMove);
    window.addEventListener('pointerup', stop);
    window.addEventListener('pointercancel', stop);
    return () => {
      window.removeEventListener('pointermove', handleMove);
      window.removeEventListener('pointerup', stop);
      window.removeEventListener('pointercancel', stop);
    };
  }, [dragging, orientation, onResize]);

  return (
    <div
      className={`splitter ${orientation === 'horizontal' ? 'splitter--horizontal' : ''} ${dragging ? 'is-dragging' : ''} ${className}`}
      onPointerDown={(event) => {
        startRef.current = orientation === 'vertical' ? event.clientX : event.clientY;
        setDragging(true);
        event.currentTarget.setPointerCapture(event.pointerId);
      }}
      role="separator"
      aria-orientation={orientation}
    />
  );
}
