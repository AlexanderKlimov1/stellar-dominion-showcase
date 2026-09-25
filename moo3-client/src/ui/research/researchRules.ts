import type {
  PlayerResearch,
  ResearchCategory,
  ResearchLevel,
  ResearchTree,
  TurnReport,
} from '../../api/types';

/**
 * Правила дерева технологий, нужные экрану исследований — п. 9.
 *
 * Раздел проходится строго по порядку, ответвлений нет, поэтому «где игрок находится
 * в разделе» — это один номер: уровень, идущий за самым верхним изученным. Экран
 * показывает по каждому разделу именно его, как окно CHANGE CURRENT RESEARCH в MOO II.
 *
 * Считает всё сервер; здесь только выборка из уже полученного состояния.
 */

/** Уровень, который исследуется в разделе следующим. */
export const nextLevelOrder = (research: PlayerResearch, categoryCode: string): number =>
  research.acquired
    .filter((technology) => technology.categoryCode === categoryCode)
    .reduce((max, technology) => Math.max(max, technology.levelOrder), 0) + 1;

/** Сам этот уровень; `null` — раздел пройден до конца, изучать в нём больше нечего. */
export const nextLevel = (
  category: ResearchCategory,
  research: PlayerResearch,
): ResearchLevel | null => {
  const order = nextLevelOrder(research, category.code);
  return category.levels.find((level) => level.order === order) ?? null;
};

export const isAcquired = (
  research: PlayerResearch,
  categoryCode: string,
  optionCode: string,
): boolean =>
  research.acquired.some(
    (technology) => technology.categoryCode === categoryCode && technology.optionCode === optionCode,
  );

/** Технология, выбранная целью прямо сейчас. */
export const isCurrent = (
  research: PlayerResearch,
  categoryCode: string,
  optionCode: string,
): boolean => research.categoryCode === categoryCode && research.optionCode === optionCode;

/**
 * Уровень целиком выбран целью. Нужен общим уровням (`general`): их технологии выдаются
 * все сразу, поэтому и выбираются они не по одной, а уровнем — какая именно технология
 * записана целью на сервере, значения не имеет.
 */
export const isCurrentLevel = (
  research: PlayerResearch,
  categoryCode: string,
  levelOrder: number,
): boolean => research.categoryCode === categoryCode && research.levelOrder === levelOrder;

/**
 * Уровень выдаётся целиком, а не одной технологией на выбор — п. 7 и п. 9.
 *
 * Так устроены общие уровни дерева, где выбора нет ни у кого, и **весь дерево целиком
 * у изобретательной расы**: в MOO II Creative платит одну цену уровня и получает все его
 * технологии. Экран обязан это показывать: раса выбирает уровень, а не строку в нём, и
 * подсвеченная одна технология из четырёх врала бы о том, что придёт после прорыва.
 *
 * Признак изобретательности приходит с сервера (`research.creative`) — там же, где
 * живёт само правило выдачи, поэтому разъехаться им негде.
 */
export const givesWholeLevel = (research: PlayerResearch, level: ResearchLevel): boolean =>
  level.general || research.creative === true;

/**
 * Как называется текущая цель исследования. Обычный уровень зовётся выбранной
 * технологией, а тот, что выдаётся целиком (общий или любой у изобретательной расы), —
 * самим уровнем: назвать его одной из технологий значило бы соврать — прорыв выдаст все.
 */
export const targetName = (
  tree: ResearchTree | null,
  research: PlayerResearch,
): string | undefined => {
  const level = tree?.categories
    .find((category) => category.code === research.categoryCode)
    ?.levels.find((item) => item.order === research.levelOrder);
  return level && givesWholeLevel(research, level) ? level.name : research.optionName;
};

/**
 * Пора спрашивать, что изучать дальше — п. 9.
 *
 * Прорыв освобождает цель исследования, и в MOO II игра тут же открывает окно выбора: без
 * цели доход очков следующего хода уходит в никуда (`ResearchService.advance` возвращается
 * ни с чем), а по одному сообщению в итогах хода этого не понять. Поэтому окно открывается
 * само — сразу после того, как игрок закрыл итоги хода, а не поверх них: сперва он читает,
 * что случилось, и только потом решает.
 *
 * Три условия разом, и каждое обязательно:
 *
 * * **прорыв был на этом ходу** — в итогах есть событие исследований. Открывать окно
 *   всякий раз, когда цели нет, нельзя: игрок мог снять её сам и не хотеть выбирать;
 * * **цели теперь нет** — прорыв её и освободил. Если сервер вернул новую цель (её выбрали
 *   в другом окне), спрашивать не о чем;
 * * **в дереве осталось что изучать** — иначе откроется экран, где все разделы пройдены.
 */
export const asksForNextTarget = (
  tree: ResearchTree | null,
  research: PlayerResearch | null,
  report: TurnReport | null,
): boolean => {
  if (!tree || !research || !report || research.categoryCode) {
    return false;
  }
  if (!report.events.some((event) => event.code === RESEARCH_EVENT)) {
    return false;
  }
  return tree.categories.some((category) => nextLevel(category, research) !== null);
};

/** Код события исследований в отчёте хода — тот же, что у сервера. */
const RESEARCH_EVENT = 'RESEARCH';
