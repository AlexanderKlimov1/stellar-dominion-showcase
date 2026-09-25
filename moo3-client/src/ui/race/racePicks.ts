import type { RaceDesign, RaceTrait, RaceTraitGroup } from '../../api/types';

/**
 * Правила конструктора расы, повторённые на клиенте — п. 7.
 *
 * Считать очки умеет и сервер — он же и проверяет расу при создании партии. Но экран
 * должен показывать остаток очков на каждое нажатие, а не после ответа сервера: в MOO II
 * счётчик picks меняется сразу, и по нему собирают расу. Поэтому арифметика повторена
 * здесь, а справочник особенностей и цены по-прежнему приходят с сервера.
 */

/** Все стороны конструктора по кодам — они нужны и цене, и запретам. */
function optionsByCode(design: RaceDesign | null): Map<string, RaceTrait> {
  return new Map(
    (design?.groups ?? []).flatMap((group) => group.options.map((option) => [option.code, option])),
  );
}

/**
 * Потрачено очков: сильные стороны их стоят, слабые возвращают.
 *
 * Сумма считается со знаком и может уйти в минус — раса из одних слабостей набирает
 * очков больше выданных.
 */
export function spentPicks(design: RaceDesign | null, traits: string[]): number {
  const byCode = optionsByCode(design);
  const parts = traits.reduce((total, code) => total + (byCode.get(code)?.picks ?? 0), 0);
  return parts + combinationExtra(design, traits);
}

/**
 * Сколько очков набор доплачивает за связки — п. 7.
 *
 * Цены сторон складываются, а сила иных пар умножается: «Объединение» даёт половину сверху
 * с каждого работника, «Большой мир» — больше самих работников. Выразить произведение
 * суммой нельзя, поэтому у пары своя цена, и платится она, только когда взяты обе стороны.
 */
export function combinationExtra(design: RaceDesign | null, traits: string[]): number {
  const chosen = new Set(traits);
  return (design?.combinations ?? [])
    .filter((one) => one.traits.every((code) => chosen.has(code)))
    .reduce((total, one) => total + one.picks, 0);
}

/**
 * Связки, которые уже собраны в наборе, — их и помечает кружок в списке сторон.
 *
 * Номер связки идёт от порядка в справочнике: он же задаёт цвет кружка, и у обеих сторон
 * одной связки он общий — иначе связь между строками пришлось бы искать глазами.
 */
export function activeCombinations(
  design: RaceDesign | null,
  traits: string[],
): { index: number; picks: number; names: string[] }[] {
  const chosen = new Set(traits);
  return (design?.combinations ?? [])
    .map((one, index) => ({ one, index }))
    .filter(({ one }) => one.traits.every((code) => chosen.has(code)))
    .map(({ one, index }) => ({ index, picks: one.picks, names: one.names }));
}

/**
 * В какую собранную связку входит эта сторона; пусто — ни в какую.
 *
 * Связок у стороны может быть несколько, но кружок один: берётся первая — большего в
 * строке списка не показать, а полный перечень стоит в итоге под списком.
 */
export function combinationOf(
  design: RaceDesign | null,
  traits: string[],
  code: string,
): { index: number; picks: number; names: string[] } | null {
  return activeCombinations(design, traits).find((one) =>
    (design?.combinations ?? [])[one.index].traits.includes(code),
  ) ?? null;
}

/**
 * Сколько очков вернули слабые стороны — то, что меряется потолком анти-выбора.
 *
 * Считается отдельно от потраченного, а не как «отрицательная часть суммы»: раса может
 * уложиться в бюджет и всё равно быть незаконной, продав слабостей сверх потолка.
 */
export function returnedPicks(design: RaceDesign | null, traits: string[]): number {
  const byCode = optionsByCode(design);
  return traits.reduce((total, code) => {
    const picks = byCode.get(code)?.picks ?? 0;
    return picks < 0 ? total - picks : total;
  }, 0);
}

/**
 * Что выбрано в группе. У группы-переключателя это одна сторона, у группы особых
 * способностей — сколько угодно, поэтому возвращается список, а не единственный вариант.
 */
export function chosenOptions(group: RaceTraitGroup, traits: string[]): RaceTrait[] {
  return group.options.filter((option) => traits.includes(option.code));
}

/**
 * Нажатие на сторону расы: новый набор кодов.
 *
 * Нажатие на уже горящую сторону её гасит — отдельной строки «обычные» в оригинале нет,
 * и снять взятое больше нечем. Нажатие на новую сторону в группе-переключателе сперва
 * снимает всё, что в этой группе стояло: иначе раса набрала бы и «плодовитых», и
 * «медленных» разом. В группе особых способностей соседи остаются — в MOO II всевидящий
 * бывает и скрытным, и подземным.
 *
 * Несовместимые стороны снимаются заодно с выбором (п. 7): взял литовора — фермерские
 * стороны уходят сами, как и в оригинале, где они попросту становятся недоступны.
 */
export function toggleInGroup(
  design: RaceDesign | null,
  traits: string[],
  groupCode: string,
  code: string,
): string[] {
  if (traits.includes(code)) {
    return traits.filter((chosen) => chosen !== code);
  }

  const group = design?.groups.find((candidate) => candidate.code === groupCode);
  const kept = group?.multiple
    ? traits
    : traits.filter((chosen) => !group?.options.some((option) => option.code === chosen));

  const byCode = optionsByCode(design);
  const forbidden = new Set(byCode.get(code)?.excludes ?? []);
  return [
    ...kept.filter((chosen) => !forbidden.has(chosen) && !byCode.get(chosen)?.excludes.includes(code)),
    code,
  ];
}

/**
 * Можно ли взять эту сторону при уже выбранных — п. 7.
 *
 * В MOO II недоступная сторона не исчезает, а гаснет: игрок видит и её, и цену, и то,
 * что она сейчас не берётся. Здесь так же — экран гасит строку, а не прячет её.
 * Уже выбранная сторона доступна всегда: её должно быть чем снять.
 */
export function isBlocked(design: RaceDesign | null, traits: string[], option: RaceTrait): boolean {
  if (traits.includes(option.code)) {
    return false;
  }
  const byCode = optionsByCode(design);
  return traits.some(
    (chosen) => option.excludes.includes(chosen) || Boolean(byCode.get(chosen)?.excludes.includes(option.code)),
  );
}
