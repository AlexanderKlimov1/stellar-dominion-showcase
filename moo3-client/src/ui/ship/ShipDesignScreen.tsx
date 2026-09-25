import { useEffect, useMemo, useState } from 'react';
import { gameApi } from '../../api/client';
import type {
  ShipCatalog,
  ShipComponentOption,
  ShipDesign,
  ShipHull,
  WeaponModification,
} from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { readRevealGalaxy } from '../../state/settings';
import { useModalEscape } from '../useModalEscape';
import { ShipAutomatics } from './ShipAutomatics';
import { ShipDesignList } from './ShipDesignList';
import { ShipDesignStats } from './ShipDesignStats';
import { ShipHullPicture } from './ShipHullPicture';
import { ShipModificationDialog } from './ShipModificationDialog';
import { ShipSystemDialog } from './ShipSystemDialog';
import { ShipSystemTables } from './ShipSystemTables';
import { fitsMore, isSingle, picksOf, totals, type Pick } from './shipDesignRules';
import { t, useT } from '../../i18n';

/**
 * Сцена дизайна кораблей — п. 8, окном Ship Design из MOO II во весь экран.
 *
 * <b>Проект не создают из ниоткуда — переписывают одну из шести ячеек.</b> Какую именно,
 * решают ДО окна: в оригинале нажатие DESIGN превращает указатель в выбор цели, и цель
 * берут из тех же шести проектов, что стоят в правой колонке окна стройки. Поэтому сцена
 * принимает `slot` и открывается сразу на нём — никакого своего списка на этой дороге нет.
 * <p>
 * Список проектов (`ShipDesignList`) остаётся только для входа С ЭКРАНА ФЛОТА: там шести
 * ячеек на виду нет, и указывать не на что. Оттуда же в нижней полосе стоит возврат к
 * списку — в оригинале ради смены класса приходилось выходить из окна и терять начатое, и
 * эту оплошность мы не повторяем.
 *
 * <b>Разметка снята со скриншота оригинала.</b> Сверху две плашки: название проекта слева
 * и заголовок окна справа. Под ними три сегмента в ряд: рамка с силуэтом корабля и
 * плашкой места под ней, столбец из шести классов корпуса и два ящика автоматики —
 * двигатель с бронёй в левом, щит с компьютером в правом. Нижнюю половину занимают две
 * таблицы: оружие со счётчиками и особые модули. Внизу плашки цены и свободного места и
 * три кнопки — очистить, отмена, сохранить.
 *
 * Состав меняется <b>окнами поверх</b> (`ShipSystemDialog`), а не списком сбоку: в
 * оригинале нажатие на систему открывает «SELECT WEAPON SYSTEM» и ему подобные.
 *
 * Считает проект сцена сама (`shipDesignRules.ts`): пока игрок его собирает, сохранённого
 * проекта нет и спрашивать сервер не о чем. Последнее слово всё равно за сервером — он
 * пересчитает всё при сохранении и откажет, если проект не влезает в корпус или его
 * компоненты ещё не изучены.
 */
export function ShipDesignScreen({
  slot: fromCaller,
  onClose,
}: {
  /**
   * Ячейка, названная указателем в окне стройки (п. 8); пусто — вход с экрана флота, и
   * сцена начинается со списка проектов.
   */
  slot?: number;
  onClose: () => void;
}) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const fleet = useGameStore((state) => state.fleet);
  const espionage = useGameStore((state) => state.espionage);
  const setEmpire = useGameStore((state) => state.setEmpire);
  const setMap = useGameStore((state) => state.setMap);

  const [catalog, setCatalog] = useState<ShipCatalog | null>(null);
  const [designs, setDesigns] = useState<ShipDesign[]>([]);
  /** Ячейка, которую правим; пусто — открыт список проектов (вход с экрана флота). */
  const [slot, setSlot] = useState<number | null>(fromCaller ?? null);
  const [name, setName] = useState('');
  const [hullCode, setHullCode] = useState<string | null>(null);
  const [picks, setPicks] = useState<Pick[]>([]);
  /** Гнездо, чьё окно выбора открыто; пусто — окна нет. */
  const [openSlot, setOpenSlot] = useState<string | null>(null);
  /** Строка состава, чьи модификации правят; пусто — окна нет. */
  const [modifying, setModifying] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useT();

  /*
    Esc гасится, пока открыто окно выбора: очередь модалок ведётся по порядку появления,
    а окно рождается в той же отрисовке, что и сцена, — без этого одно нажатие закрыло бы
    оба. Так же сделано на экране исследований.
  */
  useModalEscape(
    openSlot === null && modifying === null,
    // Пришли по указателю из окна стройки — за спиной списка нет, и Esc закрывает сцену.
    () => (slot === null || fromCaller !== undefined ? onClose() : setSlot(null)),
  );

  // Справочник и проекты приходят вместе: без справочника показывать нечего, а без
  // проектов не видно, какие ячейки заняты.
  useEffect(() => {
    if (!game || !credentials) {
      return;
    }
    Promise.all([
      gameApi.getShipCatalog(game.id, credentials.accessToken),
      gameApi.getShipDesigns(game.id, credentials.accessToken),
    ])
      .then(([loadedCatalog, loadedDesigns]) => {
        setCatalog(loadedCatalog);
        setDesigns(loadedDesigns);
      })
      .catch((failure: Error) => setError(failure.message));
  }, [game, credentials]);

  const hulls = catalog?.hulls ?? [];
  const components = catalog?.components ?? [];
  const hull: ShipHull | null = hulls.find((candidate) => candidate.code === hullCode) ?? null;

  /** Расовые проценты атаки и защиты — п. 7: считаются вместе с проектом. */
  const racePercent = useMemo(
    () => ({ attack: fleet?.attackPercent ?? 0, defense: fleet?.defensePercent ?? 0 }),
    [fleet],
  );

  const stats = useMemo(
    () => (hull ? totals(hull, picks, racePercent) : null),
    [hull, picks, racePercent],
  );

  /** Открывает ячейку: её проект ложится в редактор, пустая ячейка — чистый лист. */
  const openDesign = (nextSlot: number) => {
    const design = designs.find((candidate) => candidate.slot === nextSlot) ?? null;
    const first = hulls.find((candidate) => candidate.available) ?? hulls[0] ?? null;
    setSlot(nextSlot);
    setName(design?.name ?? '');
    setHullCode(design?.hullCode ?? first?.code ?? null);
    setPicks(design ? picksOf(design, components, catalog?.modifications ?? []) : []);
    setOpenSlot(null);
    setError(null);
  };

  /*
    Пришли ПО УКАЗАТЕЛЮ из окна стройки: ячейку назвали заранее, и открыть её надо со
    всем, что в ней стоит. Ждём справочника: до него заполнять нечем, а пустая сцена
    читалась бы как «проект стёрли» — так оно и выглядело, пока этого не было.
  */
  const [filled, setFilled] = useState(false);
  useEffect(() => {
    if (fromCaller === undefined || filled || catalog === null) {
      return;
    }
    openDesign(fromCaller);
    setFilled(true);
    // openDesign зависит от справочника и проектов, и обе они уже в состоянии: гоняться
    // за его тождеством в списке зависимостей незачем.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fromCaller, filled, catalog, designs]);

  /**
   * Ставит компонент в гнездо; у оружия повторное нажатие добавляет ещё один ствол.
   *
   * <b>Места в минус не бывает</b> (п. 8): не влезло — не ставим и говорим почему. Сервер
   * такой проект и не примет, но узнавать об этом при сохранении — плохой обмен: игрок
   * собирает корабль десятком нажатий и должен видеть предел сразу.
   */
  const add = (component: ShipComponentOption) => {
    if (hull && !fitsMore(hull, picks, component, racePercent)) {
      setError(t('design.noRoom', { name: component.name }));
      return;
    }
    setError(null);
    setPicks((current) => {
      const existing = current.find((pick) => pick.component.code === component.code);
      if (isSingle(component.slot)) {
        // В гнездо влезает один: новый компонент вытесняет прежний.
        const others = current.filter((pick) => pick.component.slot !== component.slot);
        return [...others, { component, count: 1, modifications: [] }];
      }
      if (component.slot === 'SPECIAL' && existing) {
        // Особый модуль ставится один раз — повторное нажатие его снимает.
        return current.filter((pick) => pick.component.code !== component.code);
      }
      if (existing) {
        // Прибавляется тот ствол, что стоит без модификаций: с модификациями он свой.
        const plain = current.find(
          (pick) => pick.component.code === component.code && pick.modifications.length === 0,
        );
        if (plain) {
          return current.map((pick) =>
            pick === plain ? { ...pick, count: pick.count + 1 } : pick,
          );
        }
      }
      return [...current, { component, count: 1, modifications: [] }];
    });
  };

  /**
   * Меняет модификации ствола — п. 8: строка состава с другим набором модификаций это
   * другая строка, как и в оригинале, где тяжёлые лазеры стоят отдельно от обычных.
   */
  const setModifications = (index: number, modifications: WeaponModification[]) => {
    setError(null);
    setPicks((current) =>
      current.map((pick, at) => (at === index ? { ...pick, modifications } : pick)),
    );
  };

  /**
   * Смена корпуса — п. 8: собранное переезжает на новый корпус, если помещается.
   *
   * На корпус мельче прежнего собранное влезает не всегда, и МОЛЧА выбрасывать лишнее
   * нельзя — игрок собирал его руками. Поэтому смена отменяется, а сцена говорит, что
   * снять: место корабля отрицательным не бывает ни на каком шаге.
   */
  const changeHull = (candidate: ShipHull) => {
    const after = totals(candidate, picks, racePercent);
    if (after.spaceUsed > after.space) {
      setError(t('design.hullTooSmall', {
        name: candidate.name,
        used: after.spaceUsed,
        space: after.space,
      }));
      return;
    }
    setError(null);
    setHullCode(candidate.code);
  };

  const addByCode = (code: string) => {
    const component = components.find((candidate) => candidate.code === code);
    if (component) {
      add(component);
    }
  };

  const remove = (code: string) => {
    setError(null);
    setPicks((current) =>
      current
        .map((pick) => (pick.component.code === code ? { ...pick, count: pick.count - 1 } : pick))
        .filter((pick) => pick.count > 0),
    );
  };

  /** Пустая строка окна выбора: освобождает одиночное гнездо. */
  const clearSlot = (slotCode: string) => {
    setPicks((current) => current.filter((pick) => pick.component.slot !== slotCode));
    setOpenSlot(null);
  };

  /** Кнопка «очистить» оригинала: с корабля снимается всё, корпус остаётся. */
  const clearAll = () => {
    setPicks([]);
    setError(null);
  };

  const save = () => {
    if (!game || !credentials || !hull || slot === null) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi
      .saveShipDesign(game.id, credentials.accessToken, {
        slot,
        name: name.trim() === '' ? hull.name : name.trim(),
        hullCode: hull.code,
        components: picks.map((pick) => ({
          code: pick.component.code,
          count: pick.count,
          modifications: pick.modifications.map((mod) => mod.code),
        })),
      })
      .then(async () => {
        /*
          Проект меняет и список стройки колоний, и сводку флота: обновляем всё, что на
          него смотрит, — иначе соседний экран останется с прежним проектом.

          КАРТУ здесь перечитывать обязательно, и вот почему: сохранение заводит НОВУЮ
          строку проекта (построенный корабль в MOO II остаётся с тем, с чем построен),
          а список стройки колонии держит коды `SHIP:<номер проекта>`. Без свежей карты
          окно стройки остаётся с кодом вытесненного проекта: строка корабля гаснет как
          недоступная, а цена в середине показывает прежний корабль. Проверено глазами:
          правка фрегата в крейсер гасила строку до перезагрузки страницы.
        */
        const [freshDesigns, freshFleet, freshMap] = await Promise.all([
          gameApi.getShipDesigns(game.id, credentials.accessToken),
          gameApi.getFleet(game.id, credentials.accessToken),
          gameApi.getMap(game.id, credentials.accessToken, readRevealGalaxy()),
        ]);
        setDesigns(freshDesigns);
        setEmpire(espionage, freshFleet);
        setMap(freshMap);
        /*
          Пришли по указателю из окна стройки — туда и возвращаемся: списка проектов на
          этой дороге нет вовсе (п. 8), и вываливаться в него после сохранения значит
          показать экран, которого игрок не звал.
        */
        if (fromCaller !== undefined) {
          onClose();
          return;
        }
        setSlot(null);
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  const slots = Array.from({ length: catalog?.maxDesigns ?? 6 }, (_, index) => index + 1);
  const smallHulls = catalog?.hullSizeWithoutStarBase ?? 2;

  /*
    Кораблестроения у империи может не быть вовсе — п. 8: до базовых уровней Power и
    Chemistry нет ни двигателя, ни топлива. Тогда сцена не открывается: собирать корабль
    не из чего, и честнее сказать, чего ждать, чем показать пустые гнёзда.
  */
  if (catalog !== null && !catalog.available) {
    return (
      <div className="absolute inset-0 z-40 flex items-center justify-center bg-space-950/95 p-4">
        <div className="panel w-full max-w-md">
          <h2 className="border-b border-space-700 pb-2 text-sm uppercase tracking-[0.3em] text-ink-bright">
            {t('design.title')}
          </h2>
          <p className="mt-3 text-12 leading-relaxed text-ink">{catalog.requirement}</p>
          <p className="mt-2 text-11 leading-relaxed text-ink-dim">
            {t('design.noShipbuilding')}
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

  // Первый шаг: какой класс кораблей переделываем.
  /** Ячеек шесть, как в оригинале: справочник говорит сколько, а пока он не пришёл — шесть. */
  const MAX_SLOTS = catalog?.maxDesigns ?? 6;

  if (slot === null) {
    return (
      <div className="absolute inset-0 z-40 bg-space-950">
        <ShipDesignList
          slots={slots}
          designs={designs}
          hulls={hulls}
          onOpen={openDesign}
          onClose={onClose}
        />
      </div>
    );
  }

  const fits = stats === null || stats.spaceUsed <= stats.space;

  return (
    <div className="absolute inset-0 z-40 flex flex-col bg-space-950 p-3">
      {/* Верхний ряд оригинала: название проекта слева, заголовок окна справа. */}
      <div className="flex shrink-0 items-stretch gap-3">
        <input
          type="text"
          value={name}
          maxLength={64}
          onChange={(event) => setName(event.target.value)}
          placeholder={hull?.name ?? t('design.name.placeholder')}
          aria-label={t('design.name.aria')}
          className="w-1/3 border border-space-700 bg-space-900 px-3 py-1 text-22 text-ink-bright"
        />
        <h2 className="flex flex-1 items-center justify-center border border-space-700 bg-space-900/60 text-24 uppercase tracking-[0.4em] text-ink-bright">
          {t('design.title')}
        </h2>
      </div>

      {/* Средний ряд: силуэт с плашкой места, столбец корпусов, два ящика автоматики. */}
      <div className="mt-3 flex shrink-0 gap-3">
        <div className="w-44 shrink-0">
          {/*
            СТРЕЛКИ СТОЯТ ПО БОКАМ КАРТИНКИ, а не над ней, — так они и стоят на снимке
            оригинала (`docs/moo2/ship-design.png`): узкие кнопки слева и справа от силуэта.
            Ими перелистывают ячейки, не выходя в список; начатое при этом теряется, и это
            тоже как в оригинале — ячейка открывается заново, со своим сохранённым проектом.
            Номер ячейки подписан под стрелками: в оригинале его там нет, но у нас ячейка
            это ещё и адрес сохранения, и вслепую её менять нельзя.
          */}
          <div className="flex items-center gap-1">
            <SlotArrow
              title={t('design.prevSlot')}
              onClick={() => openDesign(slot === 1 ? MAX_SLOTS : slot - 1)}
            >
              ◀
            </SlotArrow>
            <span className="min-w-0 flex-1">
              <ShipHullPicture hull={hull} />
            </span>
            <SlotArrow
              title={t('design.nextSlot')}
              onClick={() => openDesign(slot === MAX_SLOTS ? 1 : slot + 1)}
            >
              ▶
            </SlotArrow>
          </div>
          <div className="mt-1 text-center text-13 uppercase tracking-[0.2em] text-ink-dim">
            {t('design.slot', { n: slot })}
          </div>
          <div className="mt-1 flex items-baseline justify-between border border-space-700 bg-space-900/60 px-2 py-0.5">
            <span className="text-18 uppercase tracking-[0.2em] text-ink-dim">{t('design.space')}</span>
            {/* Плашка оригинала показывает место корпуса целиком; сколько его осталось —
                внизу окна, там же, где «Space Available». */}
            <span className="text-20 text-ink-bright">{stats ? stats.space : '—'}</span>
          </div>
        </div>

        {/*
          Столбец классов корпуса — в оригинале он стоит прямо у картинки, все шесть
          названий сразу, и неизученные погашены. Верфь тут ни при чём: собрать дредноут
          можно и без звёздной базы, а вот со стапеля он сойдёт только там, где она есть.
        */}
        <ul className="w-40 shrink-0 border border-space-700 bg-space-950/60 p-1">
          {hulls.map((candidate) => (
            <li key={candidate.code}>
              <button
                type="button"
                disabled={!candidate.available}
                onClick={() => changeHull(candidate)}
                title={
                  candidate.available
                    ? t('design.hull.title', { space: candidate.space, cost: candidate.cost, structure: candidate.structure })
                    : t('design.hull.locked')
                }
                className={`block w-full px-2 text-left text-20 ${
                  candidate.code === hullCode
                    ? 'bg-space-800 text-warn'
                    : candidate.available
                      ? 'text-ink hover:bg-space-800/60'
                      : 'cursor-not-allowed text-ink-faint'
                }`}
              >
                {candidate.name}
              </button>
            </li>
          ))}
        </ul>

        <div className="min-w-0 flex-1">
          <ShipAutomatics
            picks={picks}
            stats={stats}
            openSlot={openSlot}
            onOpenSlot={setOpenSlot}
          />
        </div>
      </div>

      {/* Условие стройки — короткой строкой, а не абзацем поперёк окна. */}
      {hull && hull.size > smallHulls ? (
        <p className="mt-1 shrink-0 text-13 text-warn/80">{t('design.needsStarBase')}</p>
      ) : null}

      {/* Нижняя половина окна: оружие и особые модули. */}
      <div className="mt-3 flex min-h-0 flex-1 flex-col">
        <ShipSystemTables
          hull={hull}
          picks={picks}
          openSlot={openSlot}
          onOpenSlot={setOpenSlot}
          onAdd={addByCode}
          onRemove={remove}
          canAdd={(pick) => hull === null || fitsMore(hull, picks, pick.component, racePercent)}
          onModify={setModifying}
        />
      </div>

      {error ? <p className="mt-1 shrink-0 text-18 text-danger">{error}</p> : null}

      {/* Нижняя полоса: плашки цены и места слева, кнопки оригинала справа. */}
      <div className="mt-2 flex shrink-0 flex-wrap items-center justify-between gap-3 border-t border-space-700 pt-2">
        {stats ? <ShipDesignStats stats={stats} /> : <span />}
        {/*
          Кнопки плашками, а не ссылками: в оригинале внизу окна дизайна стоят кнопки, да
          и соседние окна игры (новая игра, раса, лидеры) говорят с игроком тем же голосом.
        */}
        <span className="flex items-center gap-2 text-14">
          {/*
            «Проекты» — возврат к списку, и он есть только у входа с экрана флота: с
            указателя в окне стройки возвращаться некуда, цель уже названа (п. 8). В самом
            оригинале внизу ровно три кнопки — CLEAR, CANCEL и BUILD.
          */}
          {fromCaller === undefined ? (
            <BarButton onClick={() => setSlot(null)}>{t('design.designs')}</BarButton>
          ) : null}
          <BarButton onClick={clearAll}>{t('design.clear')}</BarButton>
          <BarButton onClick={onClose}>{t('common.cancelEsc')}</BarButton>
          <BarButton
            accent
            disabled={busy || hull === null || !fits}
            onClick={save}
          >
            {t('design.save', { n: slot })}
          </BarButton>
        </span>
      </div>

      {modifying !== null && picks[modifying] ? (
        <ShipModificationDialog
          pick={picks[modifying]}
          modifications={catalog?.modifications ?? []}
          onToggle={(modification) => {
            const current = picks[modifying].modifications;
            setModifications(
              modifying,
              current.some((mod) => mod.code === modification.code)
                ? current.filter((mod) => mod.code !== modification.code)
                : [...current, modification],
            );
          }}
          onClose={() => setModifying(null)}
        />
      ) : null}

      {openSlot !== null ? (
        <ShipSystemDialog
          slot={openSlot}
          components={components.filter((component) => component.slot === openSlot)}
          hull={hull}
          picks={picks}
          roomFor={(component) => hull === null || fitsMore(hull, picks, component, racePercent)}
          onChoose={(component) => {
            add(component);
            // Одиночное гнездо заполнено — окно своё дело сделало; у оружия и особых
            // модулей оно остаётся открытым: их ставят по нескольку подряд.
            if (isSingle(component.slot)) {
              setOpenSlot(null);
            }
          }}
          onClear={() => clearSlot(openSlot)}
          onClose={() => setOpenSlot(null)}
        />
      ) : null}
    </div>
  );
}

/**
 * Кнопка нижней полосы: та же плашка, что у окон новой игры, конструктора расы и лидеров.
 *
 * Голос у окон игры общий: там, где оригинал рисует кнопку, у нас тоже кнопка, а не
 * подчёркнутая ссылка.
 */
function BarButton({
  children,
  accent = false,
  disabled = false,
  onClick,
}: {
  children: string;
  /** Главное действие полосы — сохранение: в оригинале оно выделено. */
  accent?: boolean;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      className={
        'border px-4 py-1.5 uppercase tracking-widest '
        + (disabled
          ? 'cursor-not-allowed border-space-700 text-ink-off'
          : accent
            ? 'border-accent bg-space-800 text-accent'
            : 'border-space-600 bg-space-800 text-ink hover:text-accent')
      }
      onClick={onClick}
    >
      {children}
    </button>
  );
}

/** Стрелка перелистывания ячеек — узкая кнопка сбоку от силуэта, как в оригинале. */
function SlotArrow({
  children,
  title,
  onClick,
}: {
  children: string;
  title: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      title={title}
      className="flex-none border border-space-700 bg-space-900 px-1 py-6 text-14 text-ink-soft hover:border-accent hover:text-accent"
      onClick={onClick}
    >
      {children}
    </button>
  );
}
