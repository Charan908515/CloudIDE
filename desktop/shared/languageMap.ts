export interface LanguageRunConfig {
  lang: string;
  cmd: string | null;
  judge0Id: number;
  isProject?: boolean;
  needsCompile?: boolean;
  compilerCmd?: string;
}

export const languageMap: Record<string, LanguageRunConfig> = {
  '.py': { lang: 'python', cmd: 'python3', judge0Id: 71 },
  '.js': { lang: 'javascript', cmd: 'node', judge0Id: 63 },
  '.ts': { lang: 'typescript', cmd: 'npx ts-node', judge0Id: 74 },
  '.sh': { lang: 'bash', cmd: 'bash', judge0Id: 46 },
  '.rb': { lang: 'ruby', cmd: 'ruby', judge0Id: 72 },
  '.php': { lang: 'php', cmd: 'php', judge0Id: 68 },
  '.go': { lang: 'go', cmd: 'go run', judge0Id: 60 },
  '.rs': { lang: 'rust', cmd: 'cargo run', judge0Id: 73, isProject: true, needsCompile: true, compilerCmd: 'rustc' },
  '.c': { lang: 'c', cmd: null, judge0Id: 50, needsCompile: true, compilerCmd: 'gcc' },
  '.cpp': { lang: 'cpp', cmd: null, judge0Id: 54, needsCompile: true, compilerCmd: 'g++' },
  '.java': { lang: 'java', cmd: null, judge0Id: 62, needsCompile: true, compilerCmd: 'javac' },
};
