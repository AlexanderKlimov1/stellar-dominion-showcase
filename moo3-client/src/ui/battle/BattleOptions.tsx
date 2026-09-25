import { useState } from 'react';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';

/**
 * Настройки боя — п. 8, меню `OPTIONS` оригинала (`docs/moo2/battle-options.png`).
 *
 * В MOO II их пять: `MISSILE WARNING`, `FAST ANIMATIONS`, `SHOW LEGAL MOVES`,
 * `SHOW SHIELD ARCS`, `DISPLAY GRID`, и сверху красная `SELF DESTRUCT`.
 *
 * У нас четыре, и каждая отвечает за то, что в игре ЕСТЬ: сетка, подсветка достижимых
 * клеток, скорость залпов и журнал боя. `SHOW SHIELD ARCS` нечего показывать — щит у нас
 * один на корабль, секторов у него нет; `MISSILE WARNING` предупреждает о летящей ракете,
 * а ракета в нашем бою долетает в тот же миг, когда выпущена; `SELF DESTRUCT` — это
 * механика подрыва своего корабля, которой в игре нет вовсе.
 *
 * <b>Журнал боя — наша строка, а не оригинала.</b> В MOO II попадания видны числами прямо
 * на поле, а у нас бой считает сервер и присылает события словами: без них игрок не
 * узнает, чем кончился чужой залп. Поэтому журнал остался, но ушёл с правой трети экрана
 * в угол поля и гасится здесь же.
 */
export interface BattleOptionsState {
  /** Сетка поля — `DISPLAY GRID`. По умолчанию выключена, как и в оригинале. */
  grid: boolean;
  /** Подсветка достижимых клеток — `SHOW LEGAL MOVES`. */
  moves: boolean;
  /** Быстрые залпы — `FAST ANIMATIONS`: события пачки расходятся вдвое чаще. */
  fast: boolean;
  /** Журнал боя в углу поля. */
  log: boolean;
}

export const DEFAULT_BATTLE_OPTIONS: BattleOptionsState = {
  grid: false,
  moves: true,
  fast: false,
  log: true,
};

/** Ключ хранилища: настройки боя переживают сам бой — игрок правит их один раз. */
const KEY = 'moo3.battle.options';

/**
 * Настройки боя из браузера. Хранилище может быть недоступно (частное окно, запрет на
 * данные сайта), поэтому чтение обёрнуто: молча берём значения по умолчанию.
 */
export function readBattleOptions(): BattleOptionsState {
  try {
    const raw = window.localStorage.getItem(KEY);
    return raw ? { ...DEFAULT_BATTLE_OPTIONS, ...(JSON.parse(raw) as BattleOptionsState) } : DEFAULT_BATTLE_OPTIONS;
  } catch {
    return DEFAULT_BATTLE_OPTIONS;
  }
}

function remember(options: BattleOptionsState) {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(options));
  } catch {
    // Настройка боя — удобство, а не состояние партии: не записалась, и ладно.
  }
}

/** Меню настроек поверх полосы — столбик переключателей, как в оригинале. */
export function BattleOptions({
  options,
  onChange,
  onClose,
}: {
  options: BattleOptionsState;
  onChange: (next: BattleOptionsState) => void;
  onClose: () => void;
}) {
  const [state, setState] = useState(options);
  useT();
  useModalEscape(true, onClose);

  const toggle = (key: keyof BattleOptionsState) => {
    const next = { ...state, [key]: !state[key] };
    setState(next);
    remember(next);
    onChange(next);
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-space-950/40 p-4">
      <div className="panel mb-40 w-[min(22rem,90vw)] p-2">
        <header className="mb-2 border border-space-600 bg-space-800 py-1 text-center text-14 text-accent">
          {t('battle.options')}
        </header>
        <Toggle on={state.grid} onClick={() => toggle('grid')} label={t('battle.options.grid')} />
        <Toggle on={state.moves} onClick={() => toggle('moves')} label={t('battle.options.moves')} />
        <Toggle on={state.fast} onClick={() => toggle('fast')} label={t('battle.options.fast')} />
        <Toggle on={state.log} onClick={() => toggle('log')} label={t('battle.options.log')} />
        <button
          type="button"
          className="mt-2 w-full border border-space-600 bg-space-900 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
          onClick={onClose}
        >
          {t('common.close')}
        </button>
      </div>
    </div>
  );
}

/** Переключатель с квадратной галочкой — как в меню оригинала. */
function Toggle({ on, label, onClick }: { on: boolean; label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full items-center gap-2 px-1 py-1 text-left text-14 text-ink hover:text-accent"
    >
      <span
        className={
          'flex h-4 w-4 flex-none items-center justify-center border '
          + (on ? 'border-accent bg-accent/30 text-accent' : 'border-space-600 text-transparent')
        }
      >
        ✓
      </span>
      {label}
    </button>
  );
}
