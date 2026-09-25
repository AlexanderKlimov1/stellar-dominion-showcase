import { useState } from 'react';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';
import { GalaxyPicture } from './GalaxyPicture';

/**
 * Окно новой игры — п. 3, окно NEW GAME MOO II.
 *
 * Разметка снята с оригинала: плашка названия сверху по центру, ряд настроек картинками, у
 * каждой свой заголовок сверху и плашка текущего значения снизу, блок переключателей
 * справа от них, а внизу две кнопки по краям — «отмена» слева, «принять» справа. Доли
 * ширины взяты оттуда же: картинка примерно седьмая часть окна, плашка значения шире
 * картинки, кнопки по краям — шестая.
 *
 * **Настройка переключается нажатием по кругу** — это правило оригинала дословно: «All of
 * the New Game Menu's options work by clicking until it cycles round to what you want —
 * there are no drop-down lists or radio button groups» (StrategyWiki, Starting a game).
 * Поэтому здесь нет ни списков, ни переключателей-радио: щелчок по картинке даёт следующее
 * значение, а по последнему — первое.
 *
 * Пояснений на сцене нет намеренно. Настроек три, каждая названа заголовком и подписана
 * значением; абзац о том, что такое случайные события и сколько звёзд в галактике, занимал
 * пол-окна и читался один раз в жизни.
 */
interface NewGameScreenProps {
  gameName: string;
  galaxySize: string;
  totalPlayers: number;
  galacticEvents: boolean;
  onGameName: (value: string) => void;
  onGalaxySize: (value: string) => void;
  onTotalPlayers: (value: number) => void;
  onGalacticEvents: (value: boolean) => void;
  /** Дальше выбор расы — он же и создаёт партию. */
  onAccept: () => void;
  onCancel: () => void;
}

/**
 * Сколько империй бывает в партии — п. 3: столько же принимает сервер
 * ({@code CreateGameRequest.totalPlayers}, 2…8).
 *
 * Список, а не два числа с шагом: значение переключается по кругу, и крайние ступени
 * должны смыкаться — после восьми снова две.
 */
const PLAYER_COUNTS = [2, 3, 4, 5, 6, 7, 8];

export function NewGameScreen({
  gameName,
  galaxySize,
  totalPlayers,
  galacticEvents,
  onGameName,
  onGalaxySize,
  onTotalPlayers,
  onGalacticEvents,
  onAccept,
  onCancel,
}: NewGameScreenProps) {
  useT();
  useModalEscape(true, onCancel);
  const galaxySizes = useGameStore((state) => state.galaxySizes);
  const [pressed, setPressed] = useState<string | null>(null);

  const index = Math.max(0, galaxySizes.findIndex((size) => size.code === galaxySize));
  const selected = galaxySizes[index];
  // Насколько картинка галактики полна: от самой мелкой к самой крупной. Считается по
  // месту в справочнике, а не по коду, — размеров в игре может стать больше.
  const fill = galaxySizes.length > 1 ? 0.62 + 0.38 * (index / (galaxySizes.length - 1)) : 1;

  const cycleSize = () => {
    if (galaxySizes.length === 0) {
      return;
    }
    onGalaxySize(galaxySizes[(index + 1) % galaxySizes.length].code);
  };

  const cyclePlayers = () => {
    const at = PLAYER_COUNTS.indexOf(totalPlayers);
    onTotalPlayers(PLAYER_COUNTS[(at + 1) % PLAYER_COUNTS.length]);
  };

  return (
    <div className="absolute inset-0 z-50 flex justify-center overflow-y-auto bg-space-950 p-4">
      {/*
        Окно, а не экран во всю ширину: в оригинале новая игра — именно окно посреди
        экрана, и его пропорции (примерно 8:5) держат разметку узнаваемой. Ширина взята
        от окна браузера с потолком: на широком мониторе окно не растягивается в ленту.
      */}
      <section className="my-auto w-[min(92vw,46rem)] border border-space-600 bg-space-900/95 p-[3%] shadow-lg shadow-black/60">
        {/* Плашка названия — по центру сверху, как в оригинале. */}
        <h2 className="mx-auto mb-[3%] w-[52%] border border-space-600 bg-space-800 py-2 text-center text-20 uppercase tracking-[0.35em] text-ink-bright">
          {t('menu.newGame')}
        </h2>

        {/*
          Название партии. Своего места в оригинале у него нет — там партию не называют
          вовсе, — поэтому строка стоит под плашкой названия и занимает ту же ширину:
          лишнего сегмента в разметке не заводим.
        */}
        <label className="mx-auto mb-[4%] flex w-[52%] items-baseline gap-2 text-12 text-ink-dim">
          <span className="whitespace-nowrap">{t('menu.gameName')}</span>
          <input
            className="field"
            maxLength={128}
            value={gameName}
            onChange={(event) => onGameName(event.target.value)}
          />
        </label>

        <div className="flex items-start justify-between gap-[4%]">
          <Option
            title={t('menu.galaxySize')}
            value={selected?.label ?? galaxySize}
            note={selected
              ? t('menu.galaxy.summary', {
                w: selected.widthParsecs,
                h: selected.heightParsecs,
                stars: selected.totalStarCount,
              })
              : ''}
            pressed={pressed === 'size'}
            onCycle={() => {
              setPressed('size');
              cycleSize();
            }}
          >
            <GalaxyPicture stars={selected?.totalStarCount ?? 40} fill={fill} />
          </Option>

          <Option
            title={t('newGame.players')}
            value={t('newGame.players.value', { n: totalPlayers })}
            note={t('newGame.players.note')}
            pressed={pressed === 'players'}
            onCycle={() => {
              setPressed('players');
              cyclePlayers();
            }}
          >
            {/* Число во всю картинку — как цифра игроков в окне оригинала. */}
            <span className="flex h-full w-full items-center justify-center text-[3.2em] leading-none text-ink-bright">
              {totalPlayers}
            </span>
          </Option>

          {/*
            Блок переключателей — в оригинале он стоит справа от картинок и собран в свою
            рамку: тактический бой, случайные события, налёты антаран. У нас из трёх
            строк осталась одна — прочих механик в игре нет.
          */}
          <div className="w-[32%] border border-space-700 bg-space-950/60 p-3">
            <button
              type="button"
              className="flex w-full items-center gap-3 text-left text-14 text-ink"
              onClick={() => onGalacticEvents(!galacticEvents)}
            >
              <span
                className={
                  'inline-block h-4 w-4 flex-none border '
                  + (galacticEvents
                    ? 'border-accent bg-accent'
                    : 'border-space-600 bg-space-900')
                }
              />
              {t('menu.events')}
            </button>
          </div>
        </div>

        {/* Полоса кнопок: отмена слева, принять справа — как CANCEL и ACCEPT оригинала. */}
        <div className="mt-[5%] flex items-center justify-between">
          <button
            type="button"
            className="w-[18%] border border-space-600 bg-space-800 py-2 text-center text-14 uppercase tracking-widest text-ink hover:text-accent"
            onClick={onCancel}
          >
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="w-[18%] border border-accent bg-space-800 py-2 text-center text-14 uppercase tracking-widest text-accent"
            onClick={onAccept}
          >
            {t('newGame.accept')}
          </button>
        </div>
      </section>
    </div>
  );
}

/**
 * Одна настройка: заголовок сверху, картинка, плашка значения снизу.
 *
 * Доли взяты у оригинала: картинка почти квадратная и занимает примерно седьмую часть
 * ширины окна, а плашка значения шире её. Нажатие по всей картинке, а не по стрелкам, —
 * настройки оригинала переключаются именно так.
 */
function Option({
  title,
  value,
  note,
  pressed,
  onCycle,
  children,
}: {
  title: string;
  value: string;
  note: string;
  pressed: boolean;
  onCycle: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="w-[32%]">
      <div className="mb-2 text-center text-14 uppercase tracking-[0.2em] text-ink-dim">
        {title}
      </div>
      <button
        type="button"
        className={
          'mx-auto block aspect-[8/7] w-[68%] border bg-space-950 p-1 '
          + (pressed ? 'border-accent' : 'border-space-600 hover:border-accent')
        }
        title={t('newGame.cycle')}
        onClick={onCycle}
      >
        {children}
      </button>
      <div className="mx-auto mt-2 w-[85%] border border-space-700 bg-space-800 py-1 text-center text-13 text-accent">
        {value}
      </div>
      {/* Одна строка параметров — не пояснение, а сами числа выбранного. */}
      <div className="mt-1 text-center text-11 text-ink-faint">{note}</div>
    </div>
  );
}
