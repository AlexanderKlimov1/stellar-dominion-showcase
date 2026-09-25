package com.moo3.server.service;

import org.springframework.stereotype.Service;

import java.util.random.RandomGenerator;

/**
 * Правила исследований — п. 9.
 * <p>
 * Стоимость уровня — базовая цена из описания дерева ({@code cost}); она же и есть
 * стоимость технологии, потому что на уровне исследуется одна.
 * <p>
 * <b>Прорыв не наступает ровно на базовой цене.</b> Оплата базовой цены только открывает
 * возможность прорыва, дальше каждое вложенное сверху очко повышает шанс, а на двойной
 * базовой цене прорыв гарантирован. Между этими двумя точками шанс растёт линейно: обе
 * границы заданы, и линия — единственное, что проходит через них без домыслов.
 * <p>
 * <b>Отступление от MOO II, и оно намеренное.</b> В самой MOO II исследование
 * детерминированное: набралось очков по цене уровня — технология изучена, никакого броска
 * между этими событиями нет (страница {@code Calculations} StrategyWiki описывает конец
 * хода как «research projects are finished», а уровни дерева опознаются там прямо по цене
 * в очках исследований). Случайный прорыв — правило первой части, MOO I, и здесь оно
 * оставлено сознательно: срок исследования перестаёт быть арифметикой, и вложить в
 * технологию больше нужного становится осмысленным решением.
 * <p>
 * Что это значит для игрока: между оплатой уровня и выдачей технологии проходит несколько
 * ходов, и всё это время следующий уровень раздела не открывается, а технологии текущего
 * не выданы. Поэтому счётчик прорыва обязан быть виден — его показывает сегмент «Наука»
 * правой панели, иначе ожидание выглядит поломкой.
 * <p>
 * Остаток очков после прорыва пропадает: всё, что вложено сверх нужного, на следующую
 * технологию не переносится.
 */
@Service
public class ResearchRules {

    /** Множитель базовой цены, на котором прорыв гарантирован. */
    private static final int GUARANTEED_MULTIPLIER = 2;

    /** Очков не хватает до базовой цены — прорыв невозможен. */
    public Integer remainingToBaseCost(Integer levelCost, Integer researchPoints) {
        return Math.max(0, levelCost - researchPoints);
    }

    /**
     * Шанс прорыва на ближайшем ходу в процентах: 0 до оплаты базовой цены,
     * 100 на её двойном размере.
     */
    public Integer breakthroughPercent(Integer levelCost, Integer researchPoints) {
        int above = researchPoints - levelCost;
        if (above <= 0) {
            return 0;
        }
        return Math.min(100, Math.round(above * 100.0f / levelCost));
    }

    /**
     * Случился ли прорыв. Гарантированный прорыв не спрашивает генератор: на двойной
     * базовой цене технология изучается наверняка.
     */
    public Boolean breakthrough(Integer levelCost, Integer researchPoints, RandomGenerator random) {
        if (researchPoints >= levelCost * GUARANTEED_MULTIPLIER) {
            return Boolean.TRUE;
        }
        return random.nextInt(100) < breakthroughPercent(levelCost, researchPoints);
    }
}
