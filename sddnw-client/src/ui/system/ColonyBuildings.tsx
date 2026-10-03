import { useEffect, useState } from 'react';
import { gameApi } from '../../api/client';
import type { Colony, ColonyProject, Planet } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { ColonySellDialog } from './ColonySellDialog';
import { ColonySurface } from './ColonySurface';
import { PlanetInfo } from './PlanetInfo';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';

/**
 * Постройки колонии — п. 10, поверхностью планеты с экрана колонии MOO II
 * (`docs/moo2/colony.png`).
 *
 * В оригинале это самая большая область экрана: пейзаж планеты, на котором стоят
 * построенные здания, — и здесь так же (`ColonySurface`, рисунки — `buildingArt`). Прежде
 * на этом месте стояла сетка клеток с названиями: спрайтов у игры не было, и клетка с
 * подписью была заглушкой под них.
 *
 * <b>Название здания — под курсором</b>, строкой поверх пейзажа внизу: на картине оно не
 * пишется (в оригинале его нет тоже), а узнать, что стоит на поверхности, игрок обязан и
 * без подсказки браузера, которая всплывает с задержкой. Та же строка говорит, сколько
 * здание стоит в содержании и что его можно продать.
 *
 * <b>Правый щелчок по постройке продаёт её</b> — как в оригинале: рядом с указателем
 * всплывает небольшое окошко (не диалог) с названием, описанием и двумя кнопками
 * (`ColonySellDialog`). Своё меню браузера при этом гасится: другого способа отдать правый
 * щелчок игре нет. Постройка под указателем подсвечивается (`.cs-hit`).
 *
 * <b>Сам посёлок продать нельзя</b>, и правый щелчок по нему ничего не открывает: колония
 * существует, пока существует колония. Рисует его `ColonySurface` отдельно от построек —
 * у него нет ни кода в справочнике, ни цены. Под указателем он подсвечивается и показывает
 * карточку планеты (`PlanetInfo`).
 */
export function ColonyBuildings({ planet, colony, own }: {
  planet: Planet;
  colony?: Colony;
  /** Колония своя: чужую не продают. */
  own: boolean;
}) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const updatePlanet = useGameStore((state) => state.updatePlanet);

  const [selling, setSelling] = useState<{ building: ColonyProject; at: { x: number; y: number } } | null>(null);
  const [hovered, setHovered] = useState<ColonyProject | null>(null);
  const [overCentre, setOverCentre] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Что продажа убрала из стройки и очереди — трек техдолга, пункт 21 (см. `sell`).
  const [dropped, setDropped] = useState<string[]>([]);
  useT();

  // Переход к другой планете закрывает окошко: оно про постройку прежней колонии.
  useEffect(() => {
    setSelling(null);
    setHovered(null);
    setOverCentre(false);
    setError(null);
    setDropped([]);
  }, [planet.id]);

  const built = colony?.buildings ?? [];
  const soldThisTurn = colony?.soldTurn !== undefined && colony.soldTurn === game?.turn;

  const sell = () => {
    if (!game || !credentials || !selling) {
      return;
    }
    setSaving(true);
    setError(null);
    setDropped([]);
    const before = colony;
    gameApi
      .sellBuilding(game.id, planet.id, credentials.accessToken, selling.building.code)
      .then((updated) => {
        // Проданное здание меняет и выработку, и доход, и список стройки: карточка
        // колонии приходит целиком, и её же кладём в хранилище.
        updatePlanet(updated);
        // Сервер убирает из стройки и очереди то, что после продажи строить нечем
        // (`ColonyService.dropImpossibleProjects` — крупные корабли без звёздной базы), и
        // прежде молчал об этом: очередь просто становилась короче. Что именно ушло,
        // видно из разницы до и после — второго правила на клиенте для этого не нужно.
        setDropped(before && updated.colony ? lostProjects(before, updated.colony) : []);
        setSelling(null);
        setHovered(null);
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setSaving(false));
  };

  return (
    /*
      Своего `relative` у сегмента нет намеренно: окошко продажи ложится поверх всего
      экрана колонии, как в оригинале, а не внутрь поверхности, где оказалось бы
      вполовину меньше. `relative` есть только у рамки пейзажа — ради строки подписи.
    */
    <section className="flex min-h-0 flex-1 flex-col border border-space-700 bg-space-950/60">
      <div className="flex items-baseline justify-between border-b border-space-800 px-2 py-1">
        <span className="text-20 uppercase tracking-[0.2em] text-ink-dim">
          {t('colony.buildings.title')}
        </span>
        <span className={figureLabelClass('value')}>
          {built.length === 0
            ? t('colony.buildings.none')
            : tf('colony.buildings.summary', {
                n: <Figure accent="value">{built.length}</Figure>,
                upkeep: <Figure accent="value">{colony?.upkeep ?? 0}</Figure>,
              })}
        </span>
      </div>

      {dropped.length > 0 ? (
        <p className="border-b border-space-800 px-2 py-1 text-14 text-warn">
          {t('colony.sell.dropped', { projects: dropped.join(', ') })}
        </p>
      ) : null}

      <div className="relative min-h-[12rem] flex-1 overflow-hidden">
        <div className="absolute inset-0">
          <ColonySurface
            seed={planet.id}
            climate={planet.climate}
            buildings={built}
            population={planet.population}
            own={own}
            onHover={setHovered}
            onHoverCentre={setOverCentre}
            onSell={(building, at) => {
              setError(null);
              setHovered(null);
              setSelling({ building, at });
            }}
          />
        </div>
        {/* Базовое здание — сам посёлок — не продаётся, зато называет планету: карточка та
            же, что на экране системы. Слева: посёлок стоит посередине, и пейзаж читается. */}
        {overCentre && !selling ? <PlanetInfo planet={planet} side="left" /> : null}
        {hovered ? (
          <div className={'pointer-events-none absolute inset-x-0 bottom-0 bg-space-950/80 px-2 py-1 ' + figureLabelClass('value')}>
            {hovered.name}
            {hovered.upkeep > 0
              ? tf('colony.buildings.upkeep', { n: <Figure accent="value">{hovered.upkeep}</Figure> })
              : ''}
            <span className="text-ink-faint">{own ? t('colony.buildings.sellHint') : ''}</span>
          </div>
        ) : null}
      </div>

      {selling ? (
        <ColonySellDialog
          building={selling.building}
          at={selling.at}
          soldThisTurn={soldThisTurn}
          saving={saving}
          error={error}
          onSell={sell}
          onClose={() => setSelling(null)}
        />
      ) : null}
    </section>
  );
}

/**
 * Проекты, пропавшие из стройки после продажи: текущий, сменившийся товарами, и строки
 * очереди, которых не стало. Строки сверяются счётом, а не наличием: «Фрегат ×3» мог
 * превратиться в ничто целиком, и каждая копия названа по разу не будет — имя одно.
 */
function lostProjects(before: Colony, after: Colony): string[] {
  const lost = new Set<string>();
  if (before.projectCode && before.projectCode !== after.projectCode
      && after.projectCode === 'TRADE_GOODS' && before.projectName) {
    lost.add(before.projectName);
  }
  const left = new Map<string, number>();
  for (const item of after.queue) {
    left.set(item.code, (left.get(item.code) ?? 0) + 1);
  }
  for (const item of before.queue) {
    const count = left.get(item.code) ?? 0;
    if (count > 0) {
      left.set(item.code, count - 1);
    } else {
      lost.add(item.name);
    }
  }
  return [...lost];
}
