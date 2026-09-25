import type { Colony, Planet } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { ColonyLeader } from './ColonyLeader';
import { growthK } from './colonyRules';
import { useColonyJobs } from './useColonyJobs';
import { ColonyBuild } from './ColonyBuild';
import { ColonyBuildings } from './ColonyBuildings';
import { ColonyJobs } from './ColonyJobs';
import { ResourceIcon } from './colonyIcons';
import { t, useT } from '../../i18n';

/**
 * Экран управления колонией — п. 4.1 и п. 10, разметкой экрана колонии MOO II.
 *
 * Открывается двойным кликом по планете на экране звёздной системы и ложится поверх
 * него: закрыл колонию — вернулся к системе, как в оригинале.
 *
 * Экран занимает всё окно и разложен так:
 *
 *   ┌ Колония «имя» ─────────────────────────────────────────────┐
 *   │ Large · Terran · орбита 1 · владелец           доход +N кр. │
 *   ├──────────────────────────────────────────┬─────────────────┤
 *   │ ЖИТЕЛИ       N из M жителей · прирост +K  │                 │
 *   │ мешок  фермеры                       еда │     стройка     │
 *   │ молот  рабочие              производство │    (во всю      │
 *   │ микро  учёные                      наука │     высоту)     │
 *   ├──────────────────────────────────────────┤                 │
 *   │  постройки колонии (поверхность планеты) │                 │
 *   │                                            закрыть (Esc)   │
 *   └────────────────────────────────────────────────────────────┘
 *
 * Отдельной панели выработки нет: каждая величина стоит там, где её делают. Еда,
 * производство и наука — в конце строки тех жителей, что их дают; население с приростом —
 * в заголовке жителей; доход — при параметрах колонии в шапке. Так число и его причина
 * оказываются в одной строке, и глазами не приходится ходить между двумя панелями.
 *
 * Списка планет системы здесь тоже нет: соседние планеты выбираются на экране системы,
 * а экран колонии остаётся про одну колонию.
 *
 * Жители стоят сразу под шапкой: распределение меняют первым делом, и от него зависит
 * всё остальное. Под ними самая большая область — поверхность планеты с застройкой,
 * справа во всю высоту стройка.
 *
 * Своей логики у экрана нет: он раскладывает сегменты и держит распределение жителей,
 * от которого зависят сразу две панели. Считает и меняет всё сервер.
 */
export function ColonyScreen() {
  const map = useGameStore((state) => state.map);
  const openedColonyId = useGameStore((state) => state.openedColonyId);
  const setOpenedColony = useGameStore((state) => state.setOpenedColony);

  // Экран лежит поверх того, откуда его открыли, поэтому Esc забирает себе — см. useModalEscape.
  useModalEscape(openedColonyId !== null, () => setOpenedColony(null));

  /*
    Планета ищется по всей карте, а не в открытой системе: колонию открывают и
    со списка колоний империи, где никакой системы не открыто. И достаётся каждый раз
    заново: после действий игрока она заменяется в карте, и держать на неё ссылку нельзя.
  */
  const planet = openedColonyId
    ? (map?.systems
        .flatMap((system) => system.planets)
        .find((candidate) => candidate.id === openedColonyId) ?? null)
    : null;
  if (!planet) {
    return null;
  }

  return <ColonyLayout planet={planet} onOpen={setOpenedColony} />;
}

/**
 * Разметка экрана отделена от его открытия: распределение жителей считает хук, а хуки
 * нельзя звать после проверки «планета не найдена».
 */
function ColonyLayout({
  planet,
  onOpen,
}: {
  planet: Planet;
  onOpen: (planetId: string | null) => void;
}) {
  const players = useGameStore((state) => state.players);
  const map = useGameStore((state) => state.map);
  const game = useGameStore((state) => state.game);
  const { jobs, move, error, own } = useColonyJobs(planet);
  // Система колонии: губернатор служит ей, а не планете. Ищется по карте — планета своего
  // родителя не называет.
  const system = (map?.systems ?? []).find((one) =>
    one.planets.some((candidate) => candidate.id === planet.id)) ?? null;
  const turn = game?.turn ?? 1;
  useT();
  const colony = planet.colony;
  const owner = players.find((player) => player.id === planet.ownerPlayerId) ?? null;

  return (
    // Экран занимает окно целиком: в MOO II колония — полноэкранный вид, а не окошко.
    <div className="absolute inset-0 z-40 flex flex-col overflow-auto bg-space-950 p-4">
      <ColonyHeader planet={planet} colony={colony}
                    ownerName={owner?.name} ownerColor={owner?.color} />

      {/*
        Стройка — столбец во всю высоту справа: там же, где стояла выработка. Слева
        жители и под ними застройка во всю ширину.

        Строка растёт до своего содержимого, а лишнее уходит в прокрутку всего экрана:
        на невысоком окне крупные надписи не влезают, и лучше прокрутить экран, чем
        срезать нижнюю панель — со срезом стройка уезжала под нижнюю полосу.
      */}
      <div className="flex flex-1 gap-3">
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          <ColonyJobs planet={planet} colony={colony} jobs={jobs} move={move} error={error}
                      own={own} growth={colony ? growthK(planet, colony, jobs) : 0} />
          <ColonyBuildings planet={planet} colony={colony} own={own} />
        </div>

        <div className="flex w-[22rem] shrink-0 flex-col gap-3 2xl:w-[28rem]">
          <ColonyBuild planet={planet} own={own} />
          {/*
            Губернатор системы — правый нижний угол, как в оригинале
            (`docs/moo2/colony.png`): колониальный лидер служит в СИСТЕМЕ и помогает всем
            её колониям, и смотреть на него удобнее там, где смотрят на колонию. Только
            своей колонии: чужой губернатор игрока не касается.
          */}
          {own && system ? <ColonyLeader systemId={system.id} turn={turn} /> : null}
        </div>
      </div>

      <div className="mt-3 flex justify-end border-t border-space-700 pt-2 text-24">
        <button type="button" className="link" onClick={() => onOpen(null)}>
          {t('common.closeEsc')}
        </button>
      </div>
    </div>
  );
}

/**
 * Шапка: название колонии, класс планеты и владелец — как «Colony of …» в оригинале.
 *
 * Доход стоит здесь же, в конце строки параметров: деньги — единственная величина, за
 * которой не стоят чьи-то руки, поэтому в строках жителей ей места нет. Население с
 * приростом ушло в заголовок жителей — их считают по головам, там оно и к месту.
 */
function ColonyHeader({
  planet,
  colony,
  ownerName,
  ownerColor,
}: {
  planet: Planet;
  colony?: Colony;
  ownerName?: string;
  ownerColor?: string;
}) {
  return (
    <div className="mb-3 border-b border-space-700 pb-2">
      <h2 className="text-28 uppercase tracking-[0.3em] text-ink-bright">
        {t('colony.title', { name: planet.name })}
      </h2>

      <div className="mt-1 flex items-baseline justify-between gap-6">
        <p className="text-22 text-ink-faint">
          {t('colony.params', { size: planet.sizeLabel, climate: planet.climateLabel, minerals: planet.mineralsLabel, orbit: planet.orbit })}
          {ownerName ? (
            <>
              {' · '}
              <span className="inline-block h-4 w-4 align-middle" style={{ backgroundColor: ownerColor }} />{' '}
              <span className="text-ink-soft">{ownerName}</span>
              {planet.homeworld ? <span className="text-accent">{t('colony.homeworld')}</span> : null}
            </>
          ) : null}
        </p>

        {colony ? (
          <p className="flex shrink-0 items-center gap-2 text-22 text-ink-faint">
            {t('colony.income')}
            <span className={colony.income < 0 ? 'text-danger' : 'text-ink-bright'}>
              {colony.income >= 0 ? `+${colony.income}` : colony.income}
            </span>
            {/* Монеты золотые, а не серые как значки занятий: деньги здесь одни, и в строке
                параметров их видно сразу. */}
            <ResourceIcon kind="coins" className="h-7 w-7 shrink-0 text-warn" />
            {colony.upkeep > 0 ? (
              <span className="text-20 text-ink-faint">{t('colony.upkeep', { n: colony.upkeep })}</span>
            ) : null}
            {/*
              Загрязнение (п. 10) стоит рядом с доходом: это вторая постоянная утечка
              колонии, только платит она не кредитами, а производством. Пока грязи нет,
              в строке видно, сколько промышленности планета ещё стерпит, — без этого
              числа уборка появлялась бы как гром среди ясного неба.
            */}
            <span className="text-20 text-ink-faint">
              {colony.pollutionFree ? (
                t('colony.pollution.none')
              ) : colony.pollution > 0 ? (
                <>
                  {t('colony.pollution.cleanup')} <span className="text-warn">−{colony.pollution}</span> {t('colony.pollution.units')}
                </>
              ) : (
                t('colony.pollution.tolerates', { n: colony.pollutionTolerance })
              )}
            </span>
          </p>
        ) : null}
      </div>
    </div>
  );
}
