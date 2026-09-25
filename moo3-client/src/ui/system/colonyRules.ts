import type { Colony, Planet } from '../../api/types';

/**
 * Правила колонии, повторённые на клиенте, — п. 4.1.1 и п. 10.
 *
 * Считаются те же формулы, что на сервере, и по одной причине: житель переносится между
 * занятиями мгновенно, и выработка с приростом должны меняться вместе с ним, не дожидаясь
 * ответа. Ставки и прибавки приходят готовыми числами, поэтому повторяется только
 * арифметика. Источник истины — сервер: его ответ заменяет планету целиком.
 */

export interface Jobs {
  farmers: number;
  workers: number;
  scientists: number;
}

/**
 * Сколько производства уходит на уборку за собой — п. 10, правило MOO II.
 *
 * Из добытого вычитается твёрдая прибавка зданий (заводы и рудники не чадят), остаток
 * делится очистными сооружениями, из него вычитается терпимость планеты, и половина
 * оставшегося уходит на уборку — с округлением вверх. Считается здесь вместе с
 * выработкой по той же причине, что и всё в этом файле: жителя переносят мгновенно, и
 * грязь должна меняться вместе с ним.
 */
export const pollution = (colony: Colony, produced: number, clean: number): number => {
  if (colony.pollutionFree) {
    return 0;
  }
  const dirty = Math.max(0, produced - clean);
  const over = dirty - colony.pollutionTolerance * colony.pollutionDivisor;
  if (over <= 0) {
    return 0;
  }
  return Math.min(produced, Math.ceil(over / (2 * colony.pollutionDivisor)));
};

/**
 * Что колония даёт за ход при таком распределении жителей.
 *
 * Производство отдаётся дважды: `mined` — добытое жителями, `production` — то, что
 * осталось колонии после уборки. Стройке достаётся второе, а первое нужно, чтобы было
 * видно, откуда взялась разница.
 */
export const output = (colony: Colony, jobs: Jobs) => {
  // Переработка рециклотрона идёт с каждого жителя, а не с рабочего, — п. 10, и она же
  // вместе с твёрдым производством зданий составляет чистую часть добычи.
  const people = jobs.farmers + jobs.workers + jobs.scientists;
  const clean = colony.productionFlat + people * colony.productionPerColonist;
  const mined = jobs.workers * colony.productionPerWorker + clean;
  const waste = pollution(colony, mined, clean);
  return {
    food: jobs.farmers * colony.foodPerFarmer + colony.foodFlat,
    mined,
    pollution: waste,
    production: mined - waste,
    research: jobs.scientists * colony.researchPerScientist + colony.researchFlat,
  };
};

/**
 * Прибавка к рождаемости от домов — п. 10: всё производство колонии уходит в жильё.
 * Колония, которая дома не строит, не получает ничего.
 */
export const housingBonusPercent = (
  colony: Colony,
  production: number,
  population: number,
): number =>
  colony.projectCode === 'HOUSING' && population > 0
    ? Math.floor((production * 40) / population)
    : 0;

/**
 * Прирост населения колонии за ход в тысячах жителей — формула MOO II, п. 4.1.1.
 *
 * Подвоз еды грузовым флотом (`colony.foodDelivered`) считает сервер: он знает все
 * колонии империи, а клиент — только открытую. Здесь он просто прибавляется к своей еде,
 * иначе накормленная колония в прогнозе всё равно голодала бы.
 */
export const growthK = (planet: Planet, colony: Colony, jobs: Jobs): number => {
  const { food, production } = output(colony, jobs);
  const foodLack = Math.max(0, planet.population - food - colony.foodDelivered);
  const capacity = colony.maxPopulation;
  const freeSpace = Math.max(0, capacity - planet.population);
  const basic = Math.floor(Math.sqrt((2000 * planet.population * freeSpace) / capacity));
  const bonus = colony.medicineBonusPercent + housingBonusPercent(colony, production, planet.population);
  return Math.floor((basic * (100 + bonus)) / 100) + colony.growthFlatK - 50 * foodLack;
};

/**
 * Сколько ходов осталось до конца стройки — п. 10.
 *
 * Пусто, когда считать нечего: колония ничего не строит, строит бесконечное (дома
 * и товары стоимости не имеют) или стоит без производства — тогда срока нет вовсе.
 *
 * Живёт здесь, а не в панели стройки: срок показывают два экрана — колония и список
 * колоний империи.
 */
export const turnsLeft = (colony: Colony): number | null => {
  if (!colony.projectCode || colony.projectCost === undefined || colony.production <= 0) {
    return null;
  }
  return Math.ceil(Math.max(0, colony.projectCost - colony.projectPoints) / colony.production);
};

/**
 * Сколько ходов колония будет строить этот проект — п. 10.
 *
 * Считается по тому производству, что у колонии сейчас: оно уже за вычетом уборки
 * (п. 10) и прокорма, то есть ровно то, что уходит в стройку. Пусто, когда срока нет
 * вовсе: у домов и товаров конца не бывает, а колония без производства не достроит
 * ничего и никогда.
 *
 * Вложенное в стройку здесь не учитывается нарочно: это срок самого проекта, а не
 * остаток текущей стройки (его считает {@link turnsLeft}). Очередь ждёт своей очереди
 * с чистого листа — накопленное уйдёт в то, что строится сейчас.
 */
export const projectTurns = (colony: Colony, cost: number | undefined): number | null =>
  cost === undefined || colony.production <= 0 ? null : Math.ceil(cost / colony.production);

/**
 * Сокращение названия здания до слота — пока в слотах нет спрайтов.
 *
 * Короткие названия остаются как есть, длинные ужимаются пословно: «Исследовательская
 * лаборатория» → «Иссле. лабор.». Полное название всегда остаётся подсказкой слота.
 */
export const abbreviate = (name: string): string => {
  if (name.length <= 12) {
    return name;
  }
  return name
    .split(' ')
    .map((word) => (word.length > 6 ? `${word.slice(0, 5)}.` : word))
    .join(' ');
};
