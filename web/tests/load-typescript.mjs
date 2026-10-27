import { readFile } from 'node:fs/promises';
import ts from '../node_modules/typescript/lib/typescript.js';

// Compile the production source in memory; tests leave no generated files behind.
export async function loadTypeScript(relativePath) {
  const source = await readFile(new URL(relativePath, import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ESNext },
  });
  return import('data:text/javascript;base64,' + Buffer.from(outputText).toString('base64'));
}
