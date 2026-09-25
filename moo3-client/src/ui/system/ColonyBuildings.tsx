import { useEffect, useState } from 'react';
import { gameApi } from '../../api/client';
import type { Colony, ColonyProject, Planet } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { ColonySellDialog } from './ColonySellDialog';
import { abbreviate } from './colonyRules';
import { t, useT } from '../../i18n';

/**
 * Постройки колонии — п. 10, поверхностью планеты с экрана колонии MOO II.
 *
 * В оригинале это самая большая область экрана: пейзаж планеты, на котором стоят
 * построенные здания. Спрайтов зданий в игре пока нет, поэтому область размечена сеткой
 * слотов — по слоту на здание. Занятые подписаны сокращением названия, полное название
 * остаётся подсказкой; с появлением спрайтов останется заменить содержимое слота.
 *
 * Пустых слотов всегда видно с запас: сетка размечает место под будущую застройку, как
 * свободная поверхность планеты в оригинале. Это самая большая область экрана колонии,
 * и стоит она посередине — там же, где поверхность планеты в MOO II.
 *
 * <b>Правый щелчок по постройке продаёт её</b> — как в оригинале: всплывает окошко с
 * названием, описанием и двумя кнопками (`ColonySellDialog`). Своё меню браузера при
 * этом гасится: другого способа отдать правый щелчок игре нет.
 */
/** Минимум слотов: два полных ряда по восемь — сетка не должна выглядеть недостроенной. */
const MIN_SLOTS = 16;

export function ColonyBuildings({ planet, colony, own }: {
  planet: Planet;
  colony?: Colony;
  /** Колония своя: чужую не продают. */
  own: boolean;
}) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const updatePlanet = useGameStore((state) => state.updatePlanet);

  const [selling, setSelling] = useState<ColonyProject | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useT();

  // Переход к другой планете закрывает окошко: оно про постройку прежней колонии.
  useEffect(() => {
    setSelling(null);
    setError(null);
  }, [planet.id]);

  const built = colony?.buildings ?? [];
  const slots = Math.max(MIN_SLOTS, Math.ceil(built.length / 8) * 8);
  const soldThisTurn = colony?.soldTurn !== undefined && colony.soldTurn === game?.turn;

  const sell = () => {
    if (!game || !credentials || !selling) {
      return;
    }
    setSaving(true);
    setError(null);
    gameApi
      .sellBuilding(game.id, planet.id, credentials.accessToken, selling.code)
      .then((updated) => {
        // Проданное здание меняет и выработку, и доход, и список стройки: карточка
        // колонии приходит целиком, и её же кладём в хранилище.
        updatePlanet(updated);
        setSelling(null);
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setSaving(false));
  };

  return (
    /*
      Своего `relative` у сегмента нет намеренно: окошко продажи ложится поверх всего
      экрана колонии, как в оригинале, а не внутрь сетки построек, где оно оказалось бы
      в своей же прокрутке и вполовину меньше.
    */
    <section className="flex min-h-0 flex-1 flex-col overflow-auto border border-space-700 bg-space-950/60">
      <div className="flex items-baseline justify-between border-b border-space-800 px-2 py-1">
        <span className="text-20 uppercase tracking-[0.2em] text-ink-dim">
          {t('colony.buildings.title')}
        </span>
        <span className="text-20 text-ink-faint">
          {built.length === 0
            ? t('colony.buildings.none')
            : t('colony.buildings.summary', { n: built.length, upkeep: colony?.upkeep ?? 0 })}
        </span>
      </div>

      {/*
        Слоты делят высоту области поровну (auto-rows-fr): поверхность планеты в MOO II
        занята застройкой целиком, и сетка не должна жаться к верхнему краю.
      */}
      <ul className="grid flex-1 auto-rows-fr grid-cols-4 gap-2 p-2 sm:grid-cols-6 lg:grid-cols-8">
        {Array.from({ length: slots }).map((_, index) => {
          const building = built[index];
          return (
            <li
              key={building?.code ?? `empty-${index}`}
              className={
                'flex items-center justify-center overflow-hidden border px-1 ' +
                (building
                  ? 'border-space-600 bg-space-900/60'
                  : 'border-dashed border-space-800 bg-transparent')
              }
              title={
                building
                  ? `${building.name}${building.upkeep > 0 ? t('colony.buildings.upkeep', { n: building.upkeep }) : ''}`
                    + (own ? t('colony.buildings.sellHint') : '')
                  : undefined
              }
              onContextMenu={(event) => {
                if (!building || !own) {
                  return;
                }
                // Меню браузера здесь ни к чему: правый щелчок — это ход игрока.
                event.preventDefault();
                setError(null);
                setSelling(building);
              }}
            >
              {building ? (
                <span className="w-full break-words text-center text-20 leading-tight text-ink">
                  {abbreviate(building.name)}
                </span>
              ) : null}
            </li>
          );
        })}
      </ul>

      {selling ? (
        <ColonySellDialog
          building={selling}
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
