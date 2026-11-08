const shapes: Record<string, string> = {
  '◈': 'M2 7h2V5h2V3h4v2h2v2h2v7H9v-4H7v4H2z',
  '✦': 'M7 1h2v4h2v2h4v2h-4v2H9v4H7v-4H5V9H1V7h4V5h2z',
  '☰': 'M3 1h9v2h2v12H3V1zm2 3v2h5V4H5zm0 4v1h6V8H5zm0 3v1h6v-1H5z',
  '⤒': 'M1 5h14v9H1V5zm1-3h12v2H2V2zm5 5v2H5v2h2v2h2v-2h2V9H9V7H7z',
  '▣': 'M3 1h2v3h6V1h2v3h2v11H1V4h2V1zm0 6v6h10V7H3zm2 2h2v2H5V9zm4 0h2v2H9V9z',
  '◉': 'M5 1h6v2h2v5h-2v2H5V8H3V3h2V1zM3 11h10v2h2v2H1v-2h2v-2z',
  '▤': 'M1 2h5v1h4V2h5v12h-5v1H6v-1H1V2zm2 2v8h3V4H3zm7 0v8h3V4h-3z',
  '✓': 'M1 7h3v2h2v2h2V9h2V7h2V5h2V3h2v5h-2v2h-2v2h-2v2H6v-2H4v-2H2V9H1z',
  '▦': 'M2 1h12v14H2V1zm2 3v2h3V4H4zm5 0v2h3V4H9zM4 8v2h3V8H4zm5 0v2h3V8H9zM4 12v1h8v-1H4z',
  '⚙': 'M6 1h4v2h3v3h2v4h-2v3h-3v2H6v-2H3v-3H1V6h2V3h3V1zm0 5v4h4V6H6z',
};

export default function ValleyIcon({ glyph }: { glyph: string }) {
  return <svg className="valley-nav-icon" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
    <path fill="currentColor" fillRule="evenodd" d={shapes[glyph] ?? shapes['✦']} />
  </svg>;
}
