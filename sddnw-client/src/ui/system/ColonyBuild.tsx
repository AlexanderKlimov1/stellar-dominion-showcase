import { useEffect, useRef, useState, type ReactNode } from 'react';
import { gameApi } from '../../api/client';
import type { Colony, ColonyProject, Planet } from '../../api/types';
import { selectSelf, useGameStore } from '../../state/gameStore';
import { ColonyBuildScreen } from './ColonyBuildScreen';
import { ColonyBuyDialog } from './ColonyBuyDialog';
import { buyState } from '../colonies/BuyCoin';
import { ColonyQueue } from './ColonyQueue';
import { turnsLeft } from './colonyRules';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';
import { KeyFigure } from '../KeyFigure';

/**
 * Стройка колонии — п. 10, правой панелью экрана колонии MOO II.
 *
 * В оригинале это узкий сегмент справа: рамка с изображением того, что строится, срок
 * под ней и кнопки CHANGE и BUY. Изображений в игре пока нет, поэтому в рамке название
 * стройки; кнопки обе — «сменить» и «купить», и вторая показывает цену выкупа прямо на
 * себе: в MOO II её узнают только из окна подтверждения, а лишнее нажатие ради числа —
 * плохой обмен.
 *
 * Колония строит один проект за раз. Здание становится доступным после того, как изучена
 * его технология, и возводится на планете один раз. Дома и товары доступны с первого хода,
 * зданиями не становятся и не заканчиваются: производство уходит в рождаемость и в казну.
 *
 * Сам выбор стройки живёт в отдельной сцене (`ColonyBuildScreen`) — окне стройки MOO II
 * во весь экран: слева постройки планеты, справа корабли, в середине описание выбранного
 * и очередь колонии.
 */
export function ColonyBuild({ planet, own }: { planet: Planet; own: boolean }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const updatePlanet = useGameStore((state) => state.updatePlanet);
  const setLobby = useGameStore((state) => state.setLobby);
  // Казна нужна окошку выкупа: по ней видно, хватает ли на покупку.
  const credits = useGameStore((state) => selectSelf(state)?.credits ?? 0);
  const colony = planet.colony;

  const [saving, setSaving] = useState(false);
  /** Цепочка запросов очереди: они правят её по номерам строк и обгонять друг друга не должны. */
  const pending = useRef<Promise<unknown>>(Promise.resolve());
  const [picking, setPicking] = useState(false);
  /**
   * Выбранная строка очереди — п. 10: щелчок выбирает, второй щелчок по другой строке
   * переставляет выбранное туда. Правило одно на оба экрана, поэтому состояние есть и у
   * узкой панели, хотя клавиш (R и D) у неё нет.
   */
  const [selectedQueue, setSelectedQueue] = useState<number | null>(null);
  const [buying, setBuying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useT();

  // Переход к другой планете закрывает диалоги: они про стройку прежней колонии.
  useEffect(() => {
    setPicking(false);
    setBuying(false);
    setSelectedQueue(null);
    setError(null);
  }, [planet.id]);

  if (!colony) {
    return null;
  }

  /**
   * Любое действие над стройкой отвечает планетой целиком: у колонии от смены проекта
   * меняются и доход, и прирост. Поэтому все они идут одной дорогой.
   */
  const act = (call: () => Promise<Planet>, onDone?: () => void) => {
    if (!game || !credentials) {
      return;
    }
    setSaving(true);
    setError(null);
    call()
      .then((updated) => {
        updatePlanet(updated);
        onDone?.();
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setSaving(false));
  };

  const choose = (project: ColonyProject, keepCurrent?: boolean) =>
    act(
      () => gameApi.setProject(game!.id, planet.id, credentials!.accessToken, project.code, keepCurrent),
      // Перестановка перетаскиванием окна не закрывает: игрок ещё правит очередь.
      () => {
        if (!keepCurrent) {
          setPicking(false);
        }
      },
    );

  /**
    * В очередь — п. 10. `top` ставит проект первым: так кладёт щелчок по списку стройки,
    * а кнопка «в конец очереди» дописывает в хвост.
    */
  const enqueue = (project: ColonyProject, top?: boolean) =>
    act(() => gameApi.enqueueProject(game!.id, planet.id, credentials!.accessToken,
      project.code, top));

  /**
   * Выкуп идёт своей дорогой: он тратит казну, а казна — состояние игрока, и клиент
   * сам её не пересчитывает. Не забрать состав партии свежим — и в правой панели
   * останется прежняя сумма (те же грабли, что с грузовиками при перевозке жителей).
   */
  const buy = () => {
    if (!game || !credentials) {
      return;
    }
    setSaving(true);
    setError(null);
    gameApi
      .buyProject(game.id, planet.id, credentials.accessToken)
      .then(async (updated) => {
        updatePlanet(updated);
        const details = await gameApi.getGame(game.id);
        setLobby(details.game, details.players);
        setBuying(false);
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setSaving(false));
  };

  /**
   * Заложить шаблон — п. 10: сервер берёт из него то, что колония может построить сейчас.
   * Ничего не подошло — отказ словами, и он же ложится в строку ошибки панели.
   */
  const applyTemplate = (templateId: string) =>
    act(() => gameApi.enqueueTemplate(game!.id, planet.id, credentials!.accessToken, templateId));

  const buildMode = (mode: { repeatBuild?: boolean; autoBuild?: boolean }) =>
    act(() => gameApi.setBuildMode(game!.id, planet.id, credentials!.accessToken, mode));

  const dequeue = (index: number) =>
    act(() => gameApi.removeFromQueue(game!.id, planet.id, credentials!.accessToken, index));

  /**
   * Порядок очереди меняется СРАЗУ, а ответ сервера догоняет — п. 10.
   *
   * Прежде каждое движение стрелкой уходило на сервер и на время ответа гасило всю
   * очередь: поднять пятый проект первым стоило четырёх нажатий с четырьмя замираниями.
   * Между тем переставить список клиент умеет и сам — правило тут простое, а последнее
   * слово всё равно за сервером: его ответ заменяет планету целиком.
   *
   * Запросы идут ЦЕПОЧКОЙ, а не вперегонки: сервер правит очередь по номерам строк, и
   * два движения, посчитанные от одного состояния, переставили бы не то. Сорвалось —
   * возвращаем ту планету, что была до первой догадки: врать игроку о состоянии его
   * колонии хуже, чем мигнуть списком.
   */
  const move = (index: number, toIndex: number) => {
    if (!game || !credentials || index === toIndex) {
      return;
    }
    const before = planet;
    const reordered = [...colony.queue];
    const [moved] = reordered.splice(index, 1);
    reordered.splice(toIndex, 0, moved);
    updatePlanet({ ...planet, colony: { ...colony, queue: reordered } });
    setError(null);
    pending.current = pending.current
      .then(() => gameApi.moveInQueue(game.id, planet.id, credentials.accessToken, index, toIndex))
      .then((updated) => updatePlanet(updated))
      .catch((failure: Error) => {
        setError(failure.message);
        updatePlanet(before);
      });
  };

  return (
    <section className="flex min-h-0 flex-1 flex-col border border-space-700 bg-space-950/60">
      <div className="border-b border-space-800 px-2 py-1 text-20 uppercase tracking-[0.2em] text-ink-dim">
        {t('build.title')}
      </div>

      <div className="flex min-h-0 flex-1 flex-col gap-2 p-2">
        {/*
          Место под изображение стройки — в MOO II оно занимает верх этой панели.
          Пока в рамке название: она уже размечена, и с появлением спрайтов останется
          заменить её содержимое.

          Рамка занимает остаток высоты панели, а не держит пропорцию 4:3: с крупными
          надписями пропорция уводила стройку за нижний край экрана — панель считала
          высоту от своей ширины, а не от того, сколько места ей осталось.
        */}
        <div className="flex min-h-[3rem] flex-1 flex-col items-center justify-center gap-3 overflow-hidden border border-space-700 bg-space-900/60 px-2">
          <span className="w-full break-words text-center text-22 leading-tight text-ink">
            {colony.projectName ?? t('build.nothing')}
          </span>
          {/* Главное число экрана колонии — срок стройки (пункт 13): жители и постройки
              расставляются ради него, и ответ «когда» должен читаться первым. */}
          <BuildHeadline colony={colony} />
        </div>

        <p className={figureLabelClass('value')}>{progress(colony)}</p>

        <ColonyQueue
          colony={colony}
          own={own}
          busy={saving}
          onRemove={dequeue}
          onMove={move}
          onPromote={choose}
          onEnqueue={enqueue}
          selected={selectedQueue}
          onSelect={setSelectedQueue}
          credits={credits}
          onBuy={() => setBuying(true)}
        />

        {/*
          Подсказки с действием — backlog-promo, пункт 18. Новичок оставлял родной мир на
          товарах, хотя в системе была свободная планета, а колониальная база не требует
          технологий; и копил казну, не зная, что стройку можно выкупить. Обе строки — только
          когда это правда сейчас, и каждая сразу делает то, о чём говорит.
        */}
        {own && !saving ? <BuildNudges colony={colony} credits={credits}
          onSettle={(base) => choose(base)} onBuy={() => setBuying(true)} /> : null}

        {own ? (
          /*
            Две кнопки в ряд — как в оригинале, где под рамкой стройки стоят CHANGE и BUY.
            Выкуп гаснет, когда выкупать нечего: у домов и товаров конца нет, а оплаченному
            докупать уже нечего.
          */
          <span className="mt-auto flex items-baseline justify-between gap-3">
            <button type="button" className="link-quiet text-22" onClick={() => setPicking(true)}>
              {t('build.change')}
            </button>
            <button
              type="button"
              className="link-quiet text-22 disabled:cursor-not-allowed disabled:text-ink-off"
              /*
                Гаснет по тому же правилу, что монетка у стройки (`buyState`): и когда выкупать
                нечего, и когда уже оплачено, и когда казны не хватает. Прежде ссылка гасла
                только в первом случае, и нажатие без денег заканчивалось отказом сервера.
              */
              disabled={buyState(colony, credits) !== 'ready'}
              title={
                buyState(colony, credits) === 'ready'
                  ? t('build.buy.title', { n: colony.buyCost ?? 0 })
                  : buyState(colony, credits) === 'poor'
                    ? t('build.buy.noCredits', { n: colony.buyCost ?? 0, have: credits })
                    : buyState(colony, credits) === 'paid'
                      ? t('build.buy.paid')
                      : t('build.buy.nothing')
              }
              onClick={() => setBuying(true)}
            >
              {t('build.buy')}{colony.buyCost === undefined ? '' : ` ${t('common.credits', { n: colony.buyCost })}`}
            </button>
          </span>
        ) : (
          <p className="mt-auto text-20 text-ink-faint">{t('build.ownerOnly')}</p>
        )}
        {error ? <p className="text-20 text-danger">{error}</p> : null}
      </div>

      {picking ? (
        <ColonyBuildScreen
          planet={planet}
          colony={colony}
          saving={saving}
          error={error}
          onChoose={choose}
          onEnqueue={enqueue}
          onBuildMode={buildMode}
          onTemplate={applyTemplate}
          onRemove={dequeue}
          onMove={move}
          onBuy={() => setBuying(true)}
          onClose={() => setPicking(false)}
        />
      ) : null}

      {/*
        Окно выкупа — ПОСЛЕ окна стройки: зовут его и оттуда (монетка в очереди), а при
        равном слое выше оказывается тот, кто позже в разметке.
      */}
      {buying ? (
        <ColonyBuyDialog
          colony={colony}
          credits={credits}
          saving={saving}
          error={error}
          onBuy={buy}
          onClose={() => setBuying(false)}
        />
      ) : null}
    </section>
  );
}

/**
 * Главное число стройки — backlog-promo, пункт 13: у проекта — ходов до готовности, у
 * бесконечной стройки (дома, товары) срока нет, и главным становится её отдача за ход.
 * Без производства срока нет тоже — тогда число красное: это и есть ответ.
 */
function BuildHeadline({ colony }: { colony: Colony }) {
  if (!colony.projectCode) {
    return null;
  }
  if (colony.projectCost === undefined) {
    return colony.projectCode === 'HOUSING'
      ? <KeyFigure label={t('key.housing')} value={`+${colony.housingBonusPercent ?? 0}%`} />
      : <KeyFigure label={t('key.tradeGoods')} value={`+${Math.floor(colony.production / 2)}`} />;
  }
  const turns = colony.production > 0 ? turnsLeft(colony) : null;
  return turns === null
    ? <KeyFigure label={t('key.turnsToBuild')} value="—" tone="danger" />
    // Оплаченное целиком (выкуп, возврат от продажи) достраивается в конце ЭТОГО хода — и это
    // тоже один ход, а не ноль.
    : <KeyFigure label={t('key.turnsToBuild')} value={String(Math.max(1, turns))} />;
}

/**
 * Сколько осталось: у здания — вложенное и срок, у домов и товаров — их отдача,
 * потому что они не заканчиваются.
 */
const progress = (colony: Colony): ReactNode => {
  const value = (n: number) => <Figure accent="value">{n}</Figure>;
  if (!colony.projectCode) {
    return tf('build.wasted', { n: value(colony.production) });
  }
  if (colony.projectCost === undefined) {
    return colony.projectCode === 'HOUSING'
      ? tf('build.housing', { n: value(colony.housingBonusPercent ?? 0) })
      : tf('build.tradeGoods', { n: value(Math.floor(colony.production / 2)) });
  }
  if (colony.production <= 0) {
    return tf('build.noProduction', { points: value(colony.projectPoints), cost: value(colony.projectCost) });
  }
  // Срок здесь не повторяется: он главное число панели и стоит крупно над этой строкой
  // (BuildHeadline, пункт 13), а два одинаковых числа рядом спорили бы, какое читать.
  return tf('build.progress', { points: value(colony.projectPoints), cost: value(colony.projectCost) });
};

/**
 * Что колонии стоит сделать прямо сейчас — backlog-promo, пункт 18.
 *
 * Колония на товарах или домах при свободной планете в системе — её ждёт колониальная база;
 * стройка, которую казна покрывает целиком, — её можно выкупить. Это не правила, а
 * подсказки по уже показанным числам: база видна в `available` (сервер кладёт её туда,
 * только пока есть куда селиться), а «по карману» — тот же `buyState`, что у монетки.
 */
function BuildNudges({
  colony,
  credits,
  onSettle,
  onBuy,
}: {
  colony: Colony;
  credits: number;
  onSettle: (base: ColonyProject) => void;
  onBuy: () => void;
}) {
  const idle = colony.projectCode === 'TRADE_GOODS' || colony.projectCode === 'HOUSING';
  const base = colony.available.find((project) => project.code === 'COLONY_BASE');
  const queued = colony.queue.some((project) => project.code === 'COLONY_BASE');
  if (idle && base && !queued) {
    return (
      <button type="button" className="link-quiet text-left text-16 text-warn" onClick={() => onSettle(base)}>
        {t('build.nudge.colonyBase')}
      </button>
    );
  }
  if (!idle && buyState(colony, credits) === 'ready') {
    return (
      <button type="button" className="link-quiet text-left text-16 text-warn" onClick={onBuy}>
        {t('build.nudge.buy', { n: colony.buyCost ?? 0 })}
      </button>
    );
  }
  return null;
}
