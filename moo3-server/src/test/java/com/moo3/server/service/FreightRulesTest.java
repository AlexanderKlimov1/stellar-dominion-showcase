package com.moo3.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Подвоз еды грузовым флотом — п. 4.1.1.
 * <p>
 * От этих чисел зависит, выживет ли голодающая колония, поэтому они проверены: ошибка
 * здесь стоила бы игроку жителей, и заметить её в игре было бы тяжело.
 */
class FreightRulesTest {

    private final PopulationCalculator calculator = new PopulationCalculator();

    @Test
    @DisplayName("Каждый грузовик увозит пять единиц еды за ход")
    void capacityPerFreighter() {
        assertEquals(0, calculator.freightCapacity(0));
        assertEquals(5, calculator.freightCapacity(1));
        assertEquals(15, calculator.freightCapacity(3));
    }

    @Test
    @DisplayName("Перевозка жителей занимает грузовик на единицу населения")
    void freightersPerPopulation() {
        assertEquals(0, calculator.freightersForTransfer(0));
        assertEquals(1, calculator.freightersForTransfer(1));
        assertEquals(7, calculator.freightersForTransfer(7),
                "один к одному: столько жителей везём, столько грузовиков и занято");
    }

    @Test
    @DisplayName("Без грузовиков подвоза нет")
    void nothingWithoutFleet() {
        assertEquals(List.of(0, 0), calculator.deliverFood(0, List.of(3, 4)));
    }

    @Test
    @DisplayName("Хватает на всех — кормят всех досыта")
    void feedsEveryone() {
        assertEquals(List.of(3, 4), calculator.deliverFood(10, List.of(3, 4)));
    }

    @Test
    @DisplayName("Не хватает — сперва кормят тех, кому не хватает меньше")
    void smallestDeficitsFirst() {
        // Пять единиц на колонии, которым не хватает 5 и 2: младшая спасена целиком,
        // остаток уходит старшей. Так спасённых колоний выходит больше.
        assertEquals(List.of(3, 2), calculator.deliverFood(5, List.of(5, 2)));
    }

    @Test
    @DisplayName("Излишек не выдумывается: больше пула не развезут")
    void neverMoreThanPool() {
        List<Integer> delivered = calculator.deliverFood(4, List.of(10, 10));
        assertEquals(4, delivered.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("Сытым колониям подвоз не идёт")
    void wellFedGetNothing() {
        assertEquals(List.of(0, 3), calculator.deliverFood(9, List.of(0, 3)));
    }
}
