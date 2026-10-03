import { useEffect, useState } from 'react';
import { gameApi } from '../../api/client';
import type { Planet, StarSystem } from '../../api/types';
import { selectSelf, useGameStore } from '../../state/gameStore';
import { storyOfInvasion } from '../story/storyEvents';
import { useModalEscape } from '../useModalEscape';
import { OrbitDiagram, type FieldSide } from './OrbitDiagram';
import { PlanetInfo } from './PlanetInfo';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';

/**
 * Экран звёздной системы — состав планет по п. 4.1, разметкой окна «Star System» MOO II.
 *
 * Открывается двойным кликом по звезде на карте: сцена Phaser шлёт `system:opened`,
 * React показывает этот экран поверх карты. Все данные уже пришли в составе карты
 * галактики, поэтому дополнительный запрос к серверу не нужен.
 *
 * Сегменты — те же три, что в оригинале:
 *
 *   ┌ Звёздная система SADALSUUD ─────────────────┐  заголовок по центру
 *   │ ┌карточка планеты┐                          │
 *   │ │                │     ○  ·  ✷  ·  ○        │  поле со звездой и орбитами
 *   │ └────────────────┘                          │
 *   │ сведения о системе               закрыть    │  нижняя полоса
 *   └─────────────────────────────────────────────┘
 *
 * Таблицы планет в MOO II на этом экране нет, и здесь её тоже нет: выбранную планету
 * называет карточка в углу поля, а перебираются планеты кликом по схеме. Сводный список
 * всех планет империи в оригинале живёт на отдельном экране PLANETS — ему и место.
 *
 * Двойной клик по заселённой планете открывает экран управления её колонией
 * (`ColonyScreen`) поверх этого.
 *
 * Этот же экран выбирает планету для готовой колониальной базы — п. 4.1: колония строит
 * базу, а куда селиться, решает игрок, и решает он это здесь, глядя на всю систему сразу.
 * Клиент открывает экран сам, как только база достроилась в конце хода.
 *
 * Отсюда же высаживается десант на чужую колонию этой системы — п. 12. Транспортов в игре
 * ещё нет, поэтому нападать можно только там, где уже стоит своя колония, и экран системы
 * — единственное место, где обе колонии видны рядом.
 *
 * Неразведанная система (п. 15) показывается пустой: о ней известны только положение и
 * цвет звезды. Отсюда в неё отправляется шпион — вылазка открывает состав системы и
 * знакомит с хозяевами её колоний.
 */
export function SystemScreen() {
  const system = useGameStore((state) => state.openedSystem);
  const setOpenedSystem = useGameStore((state) => state.setOpenedSystem);
  const setOpenedColony = useGameStore((state) => state.setOpenedColony);
  const updateSystem = useGameStore((state) => state.updateSystem);
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const self = useGameStore(selectSelf);
  const players = useGameStore((state) => state.players);
  const pushStories = useGameStore((state) => state.pushStories);
  const [selectedPlanetId, setSelectedPlanetId] = useState<string | null>(null);
  const [colonizing, setColonizing] = useState(false);
  const [invading, setInvading] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  /*
    Планета под указателем и половина поля, где стоит указатель, — п. 4.1. По ним всплывает
    карточка планеты: только пока указатель на планете, и в ДРУГОЙ половине поля, чтобы не
    закрыть ту самую планету, на которую смотрят.
  */
  const [hovered, setHovered] = useState<{ planetId: string; side: FieldSide } | null>(null);
  const [error, setError] = useState<string | null>(null);
  useT();

  const close = () => setOpenedSystem(null);

  // Выделение снимается при переходе к другой системе, а не при любой её перерисовке:
  // перераспределение жителей заменяет планету в карте, и объект системы становится
  // новым — но игрок остался на той же системе и на той же планете.
  useEffect(() => {
    setSelectedPlanetId(null);
    setHovered(null);
    setError(null);
    setNotice(null);
  }, [system?.id]);

  // Закрытие по Esc — экран модальный и перекрывает карту. Очередь открытых экранов
  // ведёт useModalEscape: пока поверх открыта колония, Esc принадлежит ей.
  useModalEscape(system !== null, () => setOpenedSystem(null));

  if (!system) {
    return null;
  }

  /**
   * Вход на экран управления колонией — п. 4.1. Незаселённой планетой управлять нечем,
   * поэтому её двойной клик только выделяет.
   */
  const openColony = (planetId: string) => {
    setSelectedPlanetId(planetId);
    if (system.planets.find((planet) => planet.id === planetId)?.colony) {
      setOpenedColony(planetId);
    }
  };

  // Выбранная щелчком планета — ей нижняя полоса назначает заселение и десант. Пока игрок
  // ничего не выбрал, выбрана первая: кнопкам полосы нужна цель.
  const selected =
    system.planets.find((planet) => planet.id === selectedPlanetId) ?? system.planets[0] ?? null;

  // Колония этой системы с готовой базой — та, что заселит выбранную планету.
  const base =
    system.planets.find(
      (planet) => planet.colonyBaseReady && planet.ownerPlayerId === self?.id,
    ) ?? null;

  /**
   * Десант — п. 12: половина жителей своей колонии этой системы. Половина, а не выбор
   * числа: колония, отдавшая всех, перестала бы существовать, а отдельное поле ввода
   * ради одного числа загромоздило бы нижнюю полосу.
   */
  const invader = system.planets.find(
    (planet) => planet.ownerPlayerId === self?.id && planet.population > 1,
  );
  const troops = invader ? Math.floor(invader.population / 2) : 0;

  const invade = () => {
    if (!game || !credentials || !invader || !selected) {
      return;
    }
    setInvading(true);
    setError(null);
    gameApi
      .invade(game.id, invader.id, credentials.accessToken, selected.id, troops)
      .then((result) => {
        updateSystem(result.system);
        // Исход своей высадки — сценой колонии (backlog-promo, пункт 10); строка под полосой
        // остаётся: сцену закрывают, а прочесть итог ещё раз бывает нужно.
        pushStories([storyOfInvasion(result, selected.name, selected.id,
          players.find((one) => one.id === selected.ownerPlayerId), self)]);
        setNotice(
          result.captured
            ? t('system.captured', { name: selected.name, n: result.survivors })
            : t('system.repelled', { n: result.survivors }),
        );
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setInvading(false));
  };

  const colonize = () => {
    if (!game || !credentials || !base || !selected) {
      return;
    }
    setColonizing(true);
    setError(null);
    gameApi
      .colonize(game.id, base.id, credentials.accessToken, selected.id)
      .then(updateSystem)
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setColonizing(false));
  };

  return (
    /*
      ОКНО В УГЛУ, А НЕ ЭКРАН ВО ВЕСЬ ВИД — так на снимке оригинала
      (`docs/moo2/star-system.png`): окно системы прижато к левому верхнему углу, занимает
      примерно 55 % ширины и 57 % высоты, и карта из-под него видна. Затемнения поверх
      карты в оригинале нет вовсе, и здесь его больше нет тоже.

      Поле-подложка не ловит нажатия (`pointer-events-none`), а само окно ловит: иначе
      прозрачная подложка перехватывала бы клики по карте, и открытая система запирала бы
      галактику — в оригинале же по карте можно возить и открывать соседнюю звезду, не
      закрывая окна.
    */
    <div className="pointer-events-none absolute inset-0 z-30 p-3">
      {/*
        ОКНО ЗАДАЁТ СХЕМЕ РАЗМЕР, А НЕ СХЕМА ОКНУ. Высота окна взята у оригинала (57 % экрана),
        и вместиться в неё должно всё: заголовок, поле схемы и нижняя полоса. Раньше поле
        считало свою высоту от ШИРИНЫ окна — схема держит отношение сторон 1,75, — и на
        обычном экране выходило 488 точек содержимого при 454 видимых: окно открывалось с
        полосой прокрутки, чего в оригинале нет и быть не может.

        Теперь заголовок и полоса берут своё (`flex-none`), а поле забирает остаток
        (`flex-1 min-h-0`); схема вписывается в него сама, сохраняя пропорции. `min-h-0`
        здесь обязателен: без него flex-элемент не сжимается меньше содержимого, и всё
        вернулось бы к прокрутке.

        Прокрутка оставлена (`overflow-auto`), а не запрещена: на очень низком окне или при
        самом крупном тексте заголовок с полосой могут и не поместиться, и тогда лучше
        полоса прокрутки, чем срезанная кнопка «закрыть».
      */}
      <div className="panel pointer-events-auto flex h-[min(57vh,40rem)] w-[min(55vw,58rem)] flex-col overflow-auto">
        <SystemHeader system={system} />

        {/* Поле схемы: карточка планеты всплывает поверх него в верхнем углу, как в MOO II. */}
        <div className="relative min-h-0 flex-1 border border-space-700 bg-space-950/60">
          <OrbitDiagram
            system={system}
            selectedPlanetId={selected?.id ?? null}
            onSelect={setSelectedPlanetId}
            onOpen={openColony}
            onHover={(planetId, side) =>
              setHovered(planetId ? { planetId, side } : null)}
          />
          <PlanetInfo
            planet={system.planets.find((planet) => planet.id === hovered?.planetId) ?? null}
            side={hovered?.side === 'left' ? 'right' : 'left'}
          />
        </div>

        <SystemFooter
          system={system}
          base={base}
          selected={selected}
          colonizing={colonizing}
          error={error}
          notice={notice}
          troops={troops}
          canInvade={
            invader !== undefined &&
            selected !== null &&
            selected.ownerPlayerId !== undefined &&
            selected.ownerPlayerId !== self?.id &&
            selected.population > 0
          }
          invading={invading}
          onInvade={invade}
          onColonize={colonize}
          unexplored={!system.explored}
          onClose={close}
        />
      </div>
    </div>
  );
}

/** Заголовок окна: в MOO II он один на весь экран и стоит по центру. */
function SystemHeader({ system }: { system: StarSystem }) {
  return (
    /*
      Название ПЛАШКОЙ по центру — как в оригинале (`docs/moo2/star-system.png`, «Star
      System Nazin») и как у соседних окон игры. Прежде это была строка под чертой: в
      окне на полэкрана она терялась, и окно открывалось будто без заголовка.
    */
    <h2 className="mx-auto mb-2 flex w-[70%] flex-none items-center justify-center gap-3 border border-space-600 bg-space-800 py-1 text-16 uppercase tracking-[0.3em]">
      <span
        className="inline-block h-3 w-3 rounded-full"
        style={{ backgroundColor: system.colorHex }}
      />
      {/* У неразведанной системы названия нет: сервер его не отдаёт — п. 15. */}
      <span style={{ color: system.colorHex }}>
        {system.explored ? t('system.title', { name: system.name ?? '' }) : t('system.unexplored')}
      </span>
      {system.special ? (
        <span className="text-10 tracking-[0.2em] text-accent">{t('system.special')}</span>
      ) : null}
    </h2>
  );
}

/**
 * Нижняя полоса окна. В оригинале это широкая пустая строка сообщений и кнопка CLOSE
 * справа от неё; строка сообщений занята сведениями о самой системе — им больше негде
 * стоять после того, как заголовок стал одним названием.
 *
 * Когда в системе стоит готовая колониальная база, строка сообщений занята ею: она и
 * просит выбрать планету, и селит на выбранную — п. 4.1.
 */
function SystemFooter({
  system,
  base,
  selected,
  colonizing,
  error,
  notice,
  troops,
  canInvade,
  invading,
  onInvade,
  onColonize,
  unexplored,
  onClose,
}: {
  system: StarSystem;
  /** Колония с достроенной базой; `null` — селить нечем. */
  base: Planet | null;
  selected: Planet | null;
  colonizing: boolean;
  error: string | null;
  /** Итог последнего десанта — п. 12. */
  notice: string | null;
  troops: number;
  canInvade: boolean;
  invading: boolean;
  onInvade: () => void;
  onColonize: () => void;
  /** Система не разведана — п. 15: разведать её может только флот. */
  unexplored: boolean;
  onClose: () => void;
}) {
  const colonizable = system.planets.filter((planet) => planet.colonizable).length;

  return (
    <div className="mt-3 flex flex-none items-baseline justify-between gap-6 border-t border-space-700 pt-2">
      {error ? (
        <p className="text-11 text-danger">{error}</p>
      ) : notice ? (
        <p className="text-11 text-accent">{notice}</p>
      ) : base ? (
        <p className="text-11 text-accent">
          {t('system.baseReady', { name: base.name })}
        </p>
      ) : unexplored ? (
        <p className="text-11 text-ink-dim">
          {t('system.unexplored.hint')}{' '}
          {system.occupied
            ? t('system.scan.occupied')
            : system.scanned
              ? t('system.scan.clear')
              : t('system.scan.none')}
        </p>
      ) : (
        <p className={figureLabelClass('note')}>
          {tf('system.summary', {
            colour: system.color.toLowerCase(),
            x: system.x,
            y: system.y,
            planets: <Figure accent="note">{system.planets.length}</Figure>,
            n: <Figure accent="note">{colonizable}</Figure>,
          })}
          {/*
            Сторож системы — п. 11.1: пока он жив, тут нельзя ни поселиться, ни поставить
            заставу, а вошедший флот примет бой на подлёте. На карте систему помечает
            красное кольцо, а имя чудища называется здесь.
          */}
          {system.monster ? (
            <span className="text-danger"> · {t('system.guarded', { monster: system.monster })}</span>
          ) : null}
        </p>
      )}

      <div className="flex shrink-0 items-baseline gap-5 text-xs">
        {canInvade ? (
          <button
            type="button"
            className="link disabled:cursor-not-allowed disabled:text-ink-off"
            disabled={invading || troops <= 0}
            onClick={onInvade}
            title={t('system.invade.title')}
          >
            {t('system.invade', { n: troops })}
          </button>
        ) : null}
        {base ? (
          <button
            type="button"
            className="link disabled:cursor-not-allowed disabled:text-ink-off"
            disabled={colonizing || !selected || !free(selected)}
            onClick={onColonize}
            title={
              selected && !free(selected)
                ? t('system.settle.taken')
                : t('system.settle.title')
            }
          >
            {t('system.settle')}{selected && free(selected) ? ` ${selected.name}` : ''}
          </button>
        ) : null}
        <button type="button" className="link" onClick={onClose}>
          {t('common.closeEsc')}
        </button>
      </div>
    </div>
  );
}

/** Планета свободна: ничья, незаселённая и пригодная для жизни — п. 4.1.2. */
const free = (planet: Planet): boolean =>
  planet.colonizable && planet.colony === undefined && planet.ownerPlayerId === undefined;
