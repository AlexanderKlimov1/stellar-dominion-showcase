import type { Key } from '../../i18n';
import type { GameState } from '../../state/gameStore';

/**
 * Шаги обучения — п. 3: что новичок делает в первый час и в каком порядке.
 *
 * <b>Шаг закрывается СОСТОЯНИЕМ ПАРТИИ, а не нажатием «дальше».</b> Подсказка, которую
 * пролистывают кнопкой, читается по диагонали и ничему не учит: игрок доходит до конца
 * списка, не сделав ни одного из названных действий. Здесь же следующая подсказка
 * появляется ровно тогда, когда предыдущее действие СДЕЛАНО, — и пока оно не сделано,
 * текст никуда не девается.
 *
 * Отсюда и требование к шагу: у него должна быть примета, видимая из состояния клиента.
 * Если для нового шага такой приметы нет, шага быть не должно — иначе обучение
 * застрянет на нём навсегда.
 *
 * Порядок — не «от простого к сложному», а по нужде: сперва то, без чего ход проходит
 * впустую (наука без цели теряет очки), потом то, без чего партия не двигается
 * (закончить ход), и лишь затем расселение.
 */
export interface TutorialStep {
  /**
   * Ключи заголовка и текста — ЯВНЫЕ, а не собранные из имени шага.
   *
   * Собранный ключ (`tutorial.${id}.title`) не проверяется ничем: опечатка выходит не
   * ошибкой сборки, а пустой подсказкой в игре. Здесь же тип ключа — это перечень
   * словаря, и промах ловится на месте.
   */
  title: Key;
  text: Key;
  /**
   * Другой текст, когда игрок начал иначе (backlog-promo, пункт 26): у гостя колониальный
   * корабль есть с первого хода, и шаг «изучите и постройте его» велел бы делать сделанное.
   */
  textFor?: (state: GameState) => Key | null;
  /** Шаг сделан — по состоянию партии, а не по нажатию. */
  done: (state: GameState) => boolean;
}

/** Сколько колоний у самого игрока: по ним видно, расселился он или нет. */
const ownColonies = (state: GameState): number => {
  const mine = state.credentials?.playerId;
  if (!mine || !state.map) {
    return 0;
  }
  return state.map.systems.reduce(
    (count, system) =>
      count
      + system.planets.filter(
        (planet) => planet.colony != null && planet.ownerPlayerId === mine,
      ).length,
    0,
  );
};

export const TUTORIAL_STEPS: TutorialStep[] = [
  /*
    Наука первой: до выбора цели очки исследований этого хода деваются некуда, и это
    единственная строка правой панели, которая прямо говорит «не выбрано» — а новичок
    читает её как название, а не как упрёк.
  */
  {
    title: 'tutorial.research.title',
    text: 'tutorial.research.text',
    done: (state) => Boolean(state.research?.optionCode),
  },
  /*
    Колония: до неё добираются двойным щелчком дважды подряд (звезда, потом планета), и
    сам собой этот путь не находится — на карте ничто про него не говорит.
  */
  {
    title: 'tutorial.colony.title',
    text: 'tutorial.colony.text',
    done: (state) => state.openedColonyId !== null,
  },
  /*
    Ход: без него не происходит ничего вовсе. Стоит третьим, а не первым, нарочно — ход,
    законченный до выбора науки и до взгляда на колонию, проходит впустую.
  */
  {
    title: 'tutorial.turn.title',
    text: 'tutorial.turn.text',
    done: (state) => (state.game?.turn ?? 1) > 1,
  },
  /*
    Корабль: галактика остаётся тёмной, пока в неё не прилетят, — разведывают только
    корабли (п. 15).
  */
  {
    title: 'tutorial.ship.title',
    text: 'tutorial.ship.text',
    done: (state) => (state.fleet?.fleets.length ?? 0) > 0,
  },
  /*
    Полёт: флот, построенный и оставленный у родной звезды, не даёт ничего. Шаг закрыт,
    когда хоть один флот получил курс.
  */
  {
    title: 'tutorial.fly.title',
    text: 'tutorial.fly.text',
    done: (state) => (state.fleet?.fleets ?? []).some((fleet) => Boolean(fleet.targetSystemId)),
  },
  /*
    Вторая колония — последняя цель обучения: с ней игрок прошёл всю петлю игры (наука →
    стройка → полёт → расселение) и дальше учится сам.
  */
  {
    title: 'tutorial.colonise.title',
    text: 'tutorial.colonise.text',
    textFor: (state) => (state.fleet?.fleets ?? []).some((fleet) =>
      fleet.composition.some((ship) => ship.role === 'COLONY'))
      ? 'tutorial.colonise.textShip'
      : null,
    done: (state) => ownColonies(state) > 1,
  },
];
