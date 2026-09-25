import { useEffect, useState } from 'react';
import { gameApi } from '../../api/client';
import type { Colony, ColonyProject, Planet } from '../../api/types';
import { selectSelf, useGameStore } from '../../state/gameStore';
import { ColonyBuildScreen } from './ColonyBuildScreen';
import { ColonyBuyDialog } from './ColonyBuyDialog';
import { projectTurns, turnsLeft } from './colonyRules';
import { t, useT } from '../../i18n';

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
  const [picking, setPicking] = useState(false);
  const [buying, setBuying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useT();

  // Переход к другой планете закрывает диалоги: они про стройку прежней колонии.
  useEffect(() => {
    setPicking(false);
    setBuying(false);
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

  const choose = (project: ColonyProject) =>
    act(
      () => gameApi.setProject(game!.id, planet.id, credentials!.accessToken, project.code),
      () => setPicking(false),
    );

  const enqueue = (project: ColonyProject) =>
    act(() => gameApi.enqueueProject(game!.id, planet.id, credentials!.accessToken, project.code));

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

  const dequeue = (index: number) =>
    act(() => gameApi.removeFromQueue(game!.id, planet.id, credentials!.accessToken, index));

  const move = (index: number, toIndex: number) =>
    act(() => gameApi.moveInQueue(game!.id, planet.id, credentials!.accessToken, index, toIndex));

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
        <div className="flex min-h-[3rem] flex-1 items-center justify-center overflow-hidden border border-space-700 bg-space-900/60 px-2">
          <span className="w-full break-words text-center text-22 leading-tight text-ink">
            {colony.projectName ?? t('build.nothing')}
          </span>
        </div>

        <p className="text-20 leading-tight text-ink-dim">{progress(colony)}</p>

        <BuildQueue
          colony={colony}
          own={own}
          busy={saving}
          onRemove={dequeue}
          onMove={move}
          onPromote={choose}
        />

        {own ? (
          /*
            Две кнопки в ряд — как в оригинале, где под рамкой стройки стоят CHANGE и BUY.
            Выкуп гаснет, когда выкупать нечего: у домов и товаров конца нет, а оплаченному
            докупать уже нечего.
          */
          <span className="mt-auto flex items-baseline justify-between gap-3">
            <button type="button" className="link text-22" onClick={() => setPicking(true)}>
              {t('build.change')}
            </button>
            <button
              type="button"
              className="link text-22 disabled:cursor-not-allowed disabled:text-ink-off"
              disabled={colony.buyCost === undefined}
              title={
                colony.buyCost === undefined
                  ? t('build.buy.nothing')
                  : t('build.buy.title', { n: colony.buyCost })
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

      {picking ? (
        <ColonyBuildScreen
          planet={planet}
          colony={colony}
          saving={saving}
          error={error}
          onChoose={choose}
          onEnqueue={enqueue}
          onRemove={dequeue}
          onMove={move}
          onClose={() => setPicking(false)}
        />
      ) : null}
    </section>
  );
}

/**
 * Очередь стройки — п. 10, списком в том же окне, что и в MOO II.
 *
 * Первой строкой стоит <b>то, что строится сейчас</b>, и помечено оно сроком до конца.
 * Без него очередь читалась как отдельный список, и было непонятно, что за чем идёт: в
 * оригинале строящееся — верх того же списка, а не что-то рядом с ним.
 *
 * У каждой строки — во сколько ходов она обойдётся при нынешнем производстве колонии
 * (оно уже за вычетом уборки, п. 10). У текущей стройки это срок остатка, у очереди —
 * срок самого проекта: накопленное уйдёт в то, что строится сейчас, и делить его между
 * будущими проектами не с чего. Дома и товары срока не имеют вовсе — они не кончаются.
 *
 * Правится очередь здесь же: проект убирают или двигают стрелками — панель узкая, а
 * очередь короткая, перетаскивать в ней нечего.
 */
function BuildQueue({
  colony,
  own,
  busy,
  onRemove,
  onMove,
  onPromote,
}: {
  colony: Colony;
  own: boolean;
  /** Ответ сервера ещё не пришёл: вторым нажатием очередь можно перепутать. */
  busy: boolean;
  onRemove: (index: number) => void;
  onMove: (index: number, toIndex: number) => void;
  /** Взяться за проект очереди сейчас: он уходит из очереди на стапель. */
  onPromote: (project: ColonyProject) => void;
}) {
  const queue = colony.queue;
  const current = colony.projectName ?? null;
  const left = turnsLeft(colony);

  return (
    <div className="border-t border-space-800 pt-1">
      <div className="text-18 uppercase tracking-[0.2em] text-ink-faint">{t('build.queue')}</div>
      <ol className="mt-1 flex flex-col gap-0.5">
        {/*
          Строится сейчас — верхняя строка списка. Стрелок и крестика у неё нет: стройку
          меняют кнопкой «сменить», а убрать её из списка нельзя вовсе — пустой стройки
          у колонии не бывает.
        */}
        <li className="flex items-baseline gap-1">
          <span className="w-4 shrink-0 text-right text-18 text-accent">▸</span>
          <span className="min-w-0 flex-1 break-words text-20 leading-tight text-ink-bright">
            {current ?? t('build.nothing')}
          </span>
          <span className="shrink-0 text-18 text-ink-dim">
            {left === null ? (colony.projectCost === undefined ? t('build.endless') : '—') : t('build.turns', { n: left })}
          </span>
        </li>

        {queue.map((project, index) => (
          <li key={`${project.code}-${index}`} className="flex items-baseline gap-1">
            <span className="w-4 shrink-0 text-right text-18 text-ink-faint">{index + 1}</span>
            <span className="min-w-0 flex-1 break-words text-20 leading-tight text-ink-soft">
              {project.name}
            </span>
            {/*
              Срок проекта очереди: во столько ходов он обойдётся, когда колония до него
              доберётся. Число приблизительное по самой своей природе — производство
              меняется с жителями и зданиями, — но без него очередь молчит о главном.
            */}
            <span className="shrink-0 text-18 text-ink-faint">
              {queueTurns(colony, project)}
            </span>
            {own ? (
              <span className="flex shrink-0 items-baseline gap-1 text-18">
                {/*
                  «Строить сейчас» — то, чего экрану не хватало: передумал игрок уже после
                  того, как набрал очередь, и добираться до проекта через окно выбора
                  незачем. Проект уходит из очереди на стапель, а прежняя стройка остаётся
                  в списке доступного — накопленное производство при этом на колонии.
                */}
                <button
                  type="button"
                  className="link disabled:cursor-not-allowed disabled:text-ink-off"
                  disabled={busy}
                  title={t('build.now.title')}
                  onClick={() => onPromote(project)}
                >
                  ▸
                </button>
                <button
                  type="button"
                  className="link disabled:cursor-not-allowed disabled:text-ink-off"
                  disabled={busy || index === 0}
                  title={t('build.up.title')}
                  onClick={() => onMove(index, index - 1)}
                >
                  ▲
                </button>
                <button
                  type="button"
                  className="link disabled:cursor-not-allowed disabled:text-ink-off"
                  disabled={busy || index === queue.length - 1}
                  title={t('build.down.title')}
                  onClick={() => onMove(index, index + 1)}
                >
                  ▼
                </button>
                <button
                  type="button"
                  className="link disabled:cursor-not-allowed disabled:text-ink-off"
                  disabled={busy}
                  title={t('build.remove.title')}
                  onClick={() => onRemove(index)}
                >
                  ✕
                </button>
              </span>
            ) : null}
          </li>
        ))}
      </ol>
    </div>
  );
}

/** Срок проекта очереди строкой: у бесконечного его нет, без производства — нечем считать. */
const queueTurns = (colony: Colony, project: ColonyProject): string => {
  if (project.cost === undefined) {
    return t('build.endless');
  }
  const turns = projectTurns(colony, project.cost);
  return turns === null ? '—' : t('build.turns', { n: turns });
};

/**
 * Сколько осталось: у здания — вложенное и срок, у домов и товаров — их отдача,
 * потому что они не заканчиваются.
 */
const progress = (colony: Colony): string => {
  if (!colony.projectCode) {
    return t('build.wasted', { n: colony.production });
  }
  if (colony.projectCost === undefined) {
    return colony.projectCode === 'HOUSING'
      ? t('build.housing', { n: colony.housingBonusPercent ?? 0 })
      : t('build.tradeGoods', { n: Math.floor(colony.production / 2) });
  }
  if (colony.production <= 0) {
    return t('build.noProduction', { points: colony.projectPoints, cost: colony.projectCost });
  }
  // Срок считает colonyRules: то же число стоит в списке колоний империи.
  const turns = turnsLeft(colony);
  return t('build.progress', { points: colony.projectPoints, cost: colony.projectCost }) + (turns === null ? '' : ` · ${t('build.turns', { n: turns })}`);
};
