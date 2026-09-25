import { useState } from 'react';
import type { Race, RaceTrait } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';
import { CustomRaceEmblem, RaceEmblem } from './RaceEmblem';

/**
 * Выбор расы перед партией — п. 5, окно SELECT RACE MOO II.
 *
 * Разметка та же, что у окна новой игры (`ui/newgame/`), и та же, что у оригинала: плашка
 * названия по центру сверху, поле выбора картинками, полоса кнопок внизу. В оригинале раса
 * выбирается ПОРТРЕТОМ, а не списком её свойств: портреты стоят сеткой, под каждым имя, и
 * «своя раса» — такая же клетка в той же сетке, только ведёт она в конструктор.
 *
 * **Стороны расы переехали из клеток в одну полосу под сеткой.** Прежде каждая из
 * тринадцати клеток перечисляла свои стороны с ценами: экран был стеной текста на три
 * колонки, и выбрать в нём глазами было нельзя — только вычитать. Теперь стороны
 * показываются у ТОЙ расы, на которую смотрит игрок (наведение или клавиатура), как
 * описание технологии на экране исследований. Названия сторон берутся из конструктора,
 * который у клиента уже загружен: раса приходит с сервера кодами, и разъехаться описанию
 * с ценами негде.
 *
 * Строки «сколько рас в справочнике» и «каждая раса стоит столько же, сколько своя» с
 * экрана убраны: первой в оригинале нет вовсе, а вторая вдобавок перестала быть правдой —
 * готовые расы стоят по-разному, их набор это портрет, а не покупка на бюджет.
 */
export function RaceSelectScreen({
  title,
  escapeActive,
  onPick,
  onCustom,
  onClose,
}: {
  /** Заголовок: экран открывается и на создание партии, и на вход в чужую. */
  title: string;
  /**
   * Слушает ли экран Esc. Пока поверх него открыто окошко с именами, нажатие
   * принадлежит окошку: полагаться на порядок в очереди нельзя — окошко и экран
   * рождаются в разных отрисовках одного компонента (та же беда, что на экране
   * исследований).
   */
  escapeActive: boolean;
  /** Выбрана готовая раса: в партию игрок войдёт с её набором сторон. */
  onPick: (race: Race) => void;
  onCustom: () => void;
  onClose: () => void;
}) {
  const races = useGameStore((state) => state.races);
  const design = useGameStore((state) => state.raceDesign);
  /** На какую клетку смотрит игрок; `custom` — клетка своей расы. */
  const [looking, setLooking] = useState<string | null>(null);

  useModalEscape(escapeActive, onClose);
  useT();

  // Стороны по кодам: раса называет их кодами, а названия и цена живут в конструкторе.
  const traitsByCode = new Map<string, RaceTrait>(
    (design?.groups ?? []).flatMap((group) => group.options.map((option) => [option.code, option])),
  );

  const shown = races.find((race) => race.code === looking) ?? null;
  const shownTraits = shown
    ? (shown.traits.map((code) => traitsByCode.get(code)).filter(Boolean) as RaceTrait[])
    : [];

  return (
    <div className="absolute inset-0 z-50 flex justify-center overflow-y-auto bg-space-950 p-4">
      <section className="my-auto w-[min(94vw,58rem)] border border-space-600 bg-space-900/95 p-[3%] shadow-lg shadow-black/60">
        {/* Плашка названия — по центру сверху, как в окне новой игры и в оригинале. */}
        <h2 className="mx-auto w-[52%] border border-space-600 bg-space-800 py-2 text-center text-20 uppercase tracking-[0.35em] text-ink-bright">
          {t('race.select.title')}
        </h2>
        <p className="mb-[3%] mt-1 text-center text-12 text-ink-dim">{title}</p>

        {/*
          Сетка портретов. Семь клеток в ряду: тринадцать рас и своя раса ложатся двумя
          ровными рядами, а на узком окне сетка сама становится теснее.
        */}
        <ul className="grid grid-cols-4 gap-[2%] sm:grid-cols-5 lg:grid-cols-7">
          {races.map((race, order) => (
            <li key={race.code}>
              <RaceCell
                name={race.name}
                colour={race.color}
                looking={looking === race.code}
                onLook={() => setLooking(race.code)}
                onPick={() => onPick(race)}
              >
                <RaceEmblem colour={race.color} order={order} />
              </RaceCell>
            </li>
          ))}

          {/* Своя раса — такая же клетка в той же сетке, только ведёт в конструктор. */}
          <li>
            <RaceCell
              name={t('race.custom')}
              looking={looking === 'custom'}
              onLook={() => setLooking('custom')}
              onPick={onCustom}
            >
              <span className="block h-full w-full text-accent">
                <CustomRaceEmblem />
              </span>
            </RaceCell>
          </li>
        </ul>

        {/*
          Полоса сведений: та раса, на которую смотрит игрок. Высота у неё постоянная —
          иначе сетка портретов прыгала бы вверх-вниз при каждом движении мыши.
        */}
        <div className="mt-[3%] h-[7.5rem] border border-space-700 bg-space-950/60 p-3">
          {looking === null ? (
            <p className="text-13 text-ink-faint">{t('race.select.lookHint')}</p>
          ) : shown ? (
            <>
              <div className="flex items-baseline gap-3">
                <span className="uppercase tracking-[0.2em] text-ink-bright">{shown.name}</span>
                <span className="text-12 text-ink-dim">{shown.description}</span>
              </div>
              <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-13">
                {shownTraits.map((trait) => (
                  <li key={trait.code} className="flex items-baseline gap-1">
                    <span className="text-ink-soft">{trait.name}</span>
                    <span className={trait.picks < 0 ? 'text-good' : 'text-ink-faint'}>
                      {trait.picks}
                    </span>
                  </li>
                ))}
              </ul>
            </>
          ) : (
            <div className="flex items-baseline gap-3">
              <span className="uppercase tracking-[0.2em] text-accent">{t('race.custom')}</span>
              <span className="text-12 text-ink-dim">{t('race.custom.hint')}</span>
            </div>
          )}
        </div>

        {/* Полоса кнопок: возврат слева — как CANCEL оригинала. Выбор идёт нажатием по
            клетке, отдельной кнопки «принять» в оригинале здесь нет. */}
        <div className="mt-[4%]">
          <button
            type="button"
            className="w-[18%] border border-space-600 bg-space-800 py-2 text-center text-14 uppercase tracking-widest text-ink hover:text-accent"
            onClick={onClose}
          >
            {t('race.select.toMenu')}
          </button>
        </div>
      </section>
    </div>
  );
}

/**
 * Клетка расы: картинка и плашка имени под ней — то же устройство, что у настроек окна
 * новой игры, и то же, что у портретов оригинала.
 *
 * Наведение только показывает сведения, а выбирает нажатие: узнавать, что за раса, уже
 * после выбора — плохой обмен (на экране исследований на этом уже обожглись).
 */
function RaceCell({
  name,
  colour,
  looking,
  onLook,
  onPick,
  children,
}: {
  name: string;
  /** Цвет империи на карте; у своей расы его нет — клетка берёт цвет подсветки. */
  colour?: string;
  looking: boolean;
  onLook: () => void;
  onPick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      className="block w-full text-left"
      onClick={onPick}
      onMouseEnter={onLook}
      onFocus={onLook}
    >
      <span
        className={
          'block aspect-square w-full border bg-space-950 p-2 '
          + (looking ? 'border-accent' : colour ? 'border-space-600' : 'border-accent/40')
        }
        style={looking || !colour ? undefined : { borderColor: colour + '66' }}
      >
        {children}
      </span>
      <span
        className={
          'mt-1 block truncate border border-space-700 bg-space-800 px-1 py-0.5 text-center text-11 '
          + (looking ? 'text-accent' : 'text-ink-soft')
        }
        title={name}
      >
        {name}
      </span>
    </button>
  );
}
