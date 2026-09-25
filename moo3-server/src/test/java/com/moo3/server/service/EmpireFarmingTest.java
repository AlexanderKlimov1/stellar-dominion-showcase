package com.moo3.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Расстановка фермеров ПО ВСЕЙ ИМПЕРИИ — п. 4.1.1 (журнал, п. 3.100).
 * <p>
 * Правило это заводилось не ради удобства ИИ, а ради замера: пока каждая колония кормила
 * себя сама, кормовая сторона расы прибавляла еду на КАЖДОЙ колонии сразу, и вся кормовая
 * ось оказалась переоценена — «хорошие фермеры» за семь очков мерились восемнадцатью,
 * литоворы за тринадцать — пятьюдесятью.
 * <p>
 * Ошибка здесь не падает и не видна на экране: империя просто продолжает держать в поле
 * лишних людей, а узнать об этом можно будет лишь прогоном в сотни партий. Поэтому
 * проверяется правило числами, а не глазами.
 */
class EmpireFarmingTest {

    private final PopulationCalculator calculator = new PopulationCalculator();

    /** Таблица еды колонии: {@code перФермера * фермеров}, жителей — {@code population}. */
    private List<Integer> table(Integer perFarmer, Integer population) {
        return java.util.stream.IntStream.rangeClosed(0, population)
                .boxed()
                .map(farmers -> farmers * perFarmer)
                .toList();
    }

    @Test
    @DisplayName("без грузовиков расстановка прежняя: каждая колония кормит себя сама")
    void selfFedWithoutFreighters() {
        // Земной мир (3 еды с фермера) и тундра (1 еда с фермера), по шесть жителей.
        List<List<Integer>> food = List.of(table(3, 6), table(1, 6));
        List<Integer> needs = List.of(6, 6);

        List<Integer> farmers = calculator.farmersAcrossEmpire(food, needs, 0);

        // Земному хватает двух фермеров, тундре нужны все шесть.
        assertEquals(List.of(2, 6), farmers);
    }

    @Test
    @DisplayName("с грузовиками кормит лучшая колония, а худшая уходит к станку")
    void specialisesWhenFreightAllows() {
        List<List<Integer>> food = List.of(table(3, 6), table(1, 6));
        List<Integer> needs = List.of(6, 6);

        // Тундре надо привезти все шесть единиц — это ровно два грузовика.
        List<Integer> farmers = calculator.farmersAcrossEmpire(
                food, needs, calculator.freightCapacity(2));

        assertEquals(List.of(4, 0), farmers, "кормит земной мир, тундра не пашет вовсе");
        assertTrue(farmers.get(0) + farmers.get(1) < 2 + 6,
                "людей в поле стало МЕНЬШЕ — ради этого правило и заводилось");
    }

    @Test
    @DisplayName("флота мало — специализация откатывается к самопрокорму")
    void fallsBackWhenFreightIsShort() {
        List<List<Integer>> food = List.of(table(3, 6), table(1, 6));
        List<Integer> needs = List.of(6, 6);

        // Одного грузовика (5 еды) на шесть единиц не хватает: плана с подвозом нет.
        assertEquals(List.of(2, 6),
                calculator.farmersAcrossEmpire(food, needs, calculator.freightCapacity(1)));
    }

    @Test
    @DisplayName("на безжизненном климате фермеров не держат ни при каком флоте")
    void lifelessColonyNeverFarms() {
        // Пояс астероидов: фермер не даёт ничего, сколько его ни сажай.
        List<List<Integer>> food = List.of(table(3, 9), table(0, 4));
        List<Integer> needs = List.of(9, 4);

        assertEquals(0, calculator.farmersAcrossEmpire(food, needs, 0).get(1),
                "без грузовиков тоже: сажать в поле некого, эту колонию кормит только подвоз");
        assertEquals(0, calculator.farmersAcrossEmpire(food, needs, 100).get(1));
    }

    @Test
    @DisplayName("нужда в грузовиках считается по полной специализации")
    void freightWantedIsTheSpecialisedPlan() {
        List<List<Integer>> food = List.of(table(3, 6), table(1, 6));
        List<Integer> needs = List.of(6, 6);

        // При полной специализации тундра не пашет, и все её шесть единиц едут с земного.
        assertEquals(6, calculator.freightForSpecialisation(food, needs));
        assertEquals(2, calculator.freightersForFood(6), "шесть единиц — два грузовика по пять");
        assertEquals(2, calculator.freightersForFood(10));
        assertEquals(3, calculator.freightersForFood(11), "округление ВВЕРХ: остаток тоже везут");
        assertEquals(0, calculator.freightersForFood(0));
    }

    @Test
    @DisplayName("империя из одной колонии кормится сама, и грузовики ей не нужны")
    void singleColonyNeedsNoFreight() {
        List<List<Integer>> food = List.of(table(2, 8));
        List<Integer> needs = List.of(8);

        assertEquals(List.of(4), calculator.farmersAcrossEmpire(food, needs, 0));
        assertEquals(0, calculator.freightForSpecialisation(food, needs));
    }

    @Test
    @DisplayName("при равной отдаче порядок задан списком — партия обязана повторяться")
    void equalYieldsResolveByOrder() {
        List<List<Integer>> food = List.of(table(2, 5), table(2, 5), table(2, 5));
        List<Integer> needs = List.of(5, 5, 5);

        List<Integer> farmers = calculator.farmersAcrossEmpire(food, needs, 100);

        // Кормит первая по списку: пятнадцать едоков — восемь фермеров при двух с фермера.
        assertEquals(List.of(5, 3, 0), farmers);
        assertEquals(farmers, calculator.farmersAcrossEmpire(food, needs, 100),
                "тот же ответ на тех же данных — иначе парные прогоны сравнивают удачу");
    }

    @Test
    @DisplayName("пустая империя отвечает пустотой, а не падает")
    void emptyEmpire() {
        assertEquals(List.of(), calculator.farmersAcrossEmpire(List.of(), List.of(), 0));
        assertEquals(0, calculator.freightForSpecialisation(List.of(), List.of()));
    }
}
