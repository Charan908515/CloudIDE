import { CSSProperties } from 'react';

interface CodiconProps {
  name: string;
  size?: number;
  className?: string;
  style?: CSSProperties;
  spin?: boolean;
  title?: string;
}

export default function Codicon({ name, size = 16, className = '', spin = false, style, title }: CodiconProps) {
  return (
    <span
      className={`codicon codicon-${name} ${spin ? 'codicon-modifier-spin' : ''} ${className}`}
      style={{ fontSize: size, ...style }}
      title={title}
      aria-hidden={title ? undefined : true}
    />
  );
}
