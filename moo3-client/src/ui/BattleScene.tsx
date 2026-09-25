import type { Encounter } from '../api/types';
import { useModalEscape } from './useModalEscape';
import { useT } from '../i18n';

/**
 * Итог боя, посчитанного «авто» — п. 8.
 *
 * Тактическая сцена живёт отдельно (`ui/battle/BattleScreen.tsx`): там корабли ходят по
 * полю и стреляют. Сюда игрок попадает, когда сам выбрал «авто» в диалоге встречи —
 * сервер посчитал бой одной формулой, и показывать нечего, кроме исхода и того, кто с чем
 * сходился.
 */
export function BattleScene({
  encounter,
  before,
  onClose,
}: {
  encounter: Encounter;
  /** Состав флотов до боя: в самой встрече он уже с потерями. */
  before: { yours: number; theirs: number };
  onClose: () => void;
}) {
  useModalEscape(true, onClose);
  const { t } = useT();

  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-black/95 p-6">
      <div className="panel flex w-full max-w-2xl flex-col">
        <div className="flex items-baseline justify-between border-b border-space-700 pb-2">
          <h2 className="text-sm uppercase tracking-[0.3em] text-ink-bright">
            {t('battleResult.title', { system: encounter.systemName })}
          </h2>
          <span className="text-10 uppercase tracking-[0.2em] text-ink-dim">
            {t('battleResult.auto')}
          </span>
        </div>

        <div className="mt-4 flex items-center justify-center gap-12 border border-dashed border-space-700 bg-space-950/60 py-10 text-11 text-ink-dim">
          <span className="text-center">
            <span className="block text-3xl text-accent">▲</span>
            {t('battleResult.yourFleet')}
            <span className="block text-ink-faint">
              {t('battleResult.ships', { before: before.yours, after: encounter.yourShips })}
            </span>
          </span>
          <span className="text-ink-faint">{t('battleResult.versus')}</span>
          <span className="text-center">
            <span className="block text-3xl text-danger">▼</span>
            {encounter.opponentName}
            <span className="block text-ink-faint">
              {t('battleResult.ships', { before: before.theirs, after: encounter.opponentShips })}
            </span>
          </span>
        </div>

        {encounter.outcome ? (
          <p className="mt-3 border border-space-700 bg-space-950/60 px-3 py-2 text-11 text-ink">
            {encounter.outcome}
          </p>
        ) : null}

        <p className="mt-3 text-11 leading-relaxed text-ink-dim">
          {t('battleResult.note')}
        </p>

        <div className="mt-4 flex justify-end border-t border-space-700 pt-2 text-xs">
          <button type="button" className="link" onClick={onClose}>
            {t('common.closeEsc')}
          </button>
        </div>
      </div>
    </div>
  );
}
