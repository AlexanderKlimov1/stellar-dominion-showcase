import type { InvasionResult, Player, TurnReport } from '../../api/types';

/**
 * Редкие события партии, которые показываются СЦЕНОЙ, а не строкой отчёта —
 * backlog-promo, пункт 10.
 *
 * Игрок пересказывает не числа, а истории: «сосед объявил мне войну», «у меня отняли
 * колонию». Строка в итогах хода такую историю не рассказывает — её прочитывают среди
 * десятка строк о стройке. Хозяин проекта выбрал три (02.10.2026): посол (знакомство и
 * объявление войны — одна сцена, как в MOO II), колония (взята или потеряна) и наследие
 * Стражей Wardenhold.
 *
 * <b>Событие узнаётся по КЛЮЧУ, а не по тексту</b> — то же правило, что у сцены кражи: текст
 * собран на языке игрока, а ключ один на оба языка. Подстановки читаются по месту, которое
 * им отвёл сервер (`messages*.properties`).
 */
export type Story =
  | { kind: 'contact'; empire: string; raceCode?: string; colour: string }
  | { kind: 'war'; empire: string; raceCode?: string; colour: string }
  | {
      kind: 'colony';
      /** Колония сменила хозяина: мы взяли её, нападая, или её отняли у нас, обороняющихся. */
      captured: boolean;
      /** Мы нападали (иначе — оборонялись). */
      attacker: boolean;
      colony: string;
      /** Другая сторона; пусто — когда нападали мы и имя есть в самой колонии. */
      empire?: string;
      /** Цвет того, чей флаг теперь над колонией. */
      colour: string;
      planetId?: string;
      attack?: number;
      defence?: number;
    }
  | { kind: 'guardians'; system: string; technologies: string[] };

/** Ссылка на технологию в подстановке события: `catalog.tech.<код>` — п. 3.5. */
const TECH_PREFIX = 'catalog.tech.';

const CONTACT = new Set(['turn.diplomacy.met', 'turn.diplomacy.metYou']);
const WAR = 'turn.diplomacy.warDeclared';
/** Колонию отняли: ключ → места подстановок обороны и десанта (пусто у прежних ключей). */
const COLONY_LOST: Record<string, [number, number] | null> = {
  'turn.invasion.colonyLost': null,
  'turn.invasion.colonyLostSubjects': null,
  'turn.invasion.colonyLostBy': [2, 3],
  'turn.invasion.colonyLostSubjectsBy': [3, 4],
};
const COLONY_HELD: Record<string, [number, number] | null> = {
  'turn.invasion.repelled': null,
  'turn.invasion.repelledBy': [2, 3],
};
const GUARDIANS = 'turn.specialStar.claimed';

/** Нейтральный цвет, когда империю по имени найти не удалось. */
const UNKNOWN_COLOUR = '#8a94a8';

/**
 * Сцены этого хода в порядке показа: посол, колония, наследие.
 *
 * Империя ищется по ИМЕНИ среди участников партии: подстановка события несёт имя, а не
 * идентификатор (имя хранится в базе вместе с отчётом). Совпадение имён у империй —
 * редкость, и худшее, что оно сделает, — покажет не тот портрет.
 */
export function storiesOf(report: TurnReport | null, players: Player[], selfId?: string): Story[] {
  if (!report) {
    return [];
  }
  const byName = (name: string | undefined) => players.find((player) => player.name === name);
  const self = players.find((player) => player.id === selfId);
  const stories: Story[] = [];

  for (const event of report.events) {
    const args = event.args ?? [];
    const key = event.key ?? '';
    if (CONTACT.has(key) || key === WAR) {
      const empire = byName(args[0]);
      stories.push({
        kind: key === WAR ? 'war' : 'contact',
        empire: args[0] ?? '',
        raceCode: empire?.raceCode,
        colour: empire?.color ?? UNKNOWN_COLOUR,
      });
    } else if (key in COLONY_LOST || key in COLONY_HELD) {
      const lost = key in COLONY_LOST;
      const places = lost ? COLONY_LOST[key] : COLONY_HELD[key];
      const enemy = byName(args[0]);
      stories.push({
        kind: 'colony',
        captured: lost,
        attacker: false,
        colony: args[1] ?? '',
        empire: args[0],
        // Отняли — над колонией флаг врага, отбились — свой.
        colour: lost ? enemy?.color ?? UNKNOWN_COLOUR : self?.color ?? UNKNOWN_COLOUR,
        planetId: event.planetId,
        defence: places ? Number(args[places[0]]) : undefined,
        attack: places ? Number(args[places[1]]) : undefined,
      });
    } else if (key === GUARDIANS) {
      stories.push({
        kind: 'guardians',
        system: args[0] ?? '',
        // Технологии лежат одной подстановкой через запятую (`CatalogTexts`).
        technologies: (args[1] ?? '')
          .split(', ')
          .filter((one) => one.startsWith(TECH_PREFIX))
          .map((one) => one.slice(TECH_PREFIX.length)),
      });
    }
  }

  // Порядок показа не по времени, а по весу: посол открывает сцену дипломатии, потеря
  // колонии — главное, наследие — награда напоследок.
  const rank = (story: Story) =>
    story.kind === 'war' || story.kind === 'contact' ? 0 : story.kind === 'colony' ? 1 : 2;
  return stories.sort((first, second) => rank(first) - rank(second));
}

/**
 * Своя высадка — сцена колонии сразу по ответу сервера: нападавший решал сам и узнаёт исход
 * тут же, а не в итогах хода.
 */
export function storyOfInvasion(
  result: InvasionResult,
  colony: string,
  planetId: string,
  defender: Player | undefined,
  self: Player | null,
): Story {
  return {
    kind: 'colony',
    captured: result.captured,
    attacker: true,
    colony,
    empire: defender?.name,
    colour: result.captured
      ? self?.color ?? UNKNOWN_COLOUR
      : defender?.color ?? UNKNOWN_COLOUR,
    planetId,
    attack: result.attackPower,
    defence: result.defencePower,
  };
}
