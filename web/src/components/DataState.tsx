interface DataStateProps {
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  loadingText?: string;
  empty?: boolean;
  emptyText?: string;
}

export default function DataState({
  loading = false, error, onRetry, loadingText = '正在加载…', empty = false, emptyText = '暂无数据',
}: DataStateProps) {
  if (loading) return <div className="ws-empty" role="status" aria-live="polite">{loadingText}</div>;
  if (error) return (
    <div className="ws-empty" role="alert">
      <p>{error}</p>
      {onRetry && <button type="button" className="ws-button light" onClick={onRetry}>重新加载</button>}
    </div>
  );
  if (empty) return <div className="ws-empty">{emptyText}</div>;
  return null;
}
