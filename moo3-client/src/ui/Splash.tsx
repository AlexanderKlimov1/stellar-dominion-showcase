/** Экран ожидания между переходами: подключение, создание игры, генерация галактики. */
export function Splash({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-2">
      <div className="text-sm uppercase tracking-[0.35em] text-accent">{title}</div>
      {hint ? <div className="text-xs text-ink-dim">{hint}</div> : null}
    </div>
  );
}
