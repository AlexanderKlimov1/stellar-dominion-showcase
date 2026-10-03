package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;

import java.util.Collection;
import java.util.UUID;

/**
 * Победа удержанием Wardenhold — п. 3, backlog-promo, пункт 1 (решение хозяина проекта).
 * <p>
 * <b>Зачем третий путь.</b> Покорение — победа завоевателя, совет — победа дипломата и
 * многолюдной империи. Особую звезду сторожит Страж, сильнейшее чудище галактики, и взять
 * её значит выстроить флот, а удержать — выстоять, когда к ней потянутся все соседи. Это
 * цель, видимая с первого хода и посильная за одну короткую партию, — ровно то, что нужно
 * гостю, пришедшему по ссылке, и не требует ни полного истребления соседей, ни пятидесяти
 * ходов до первого совета.
 * <p>
 * <b>Держать — значит быть у звезды одному.</b> У держателя есть хотя бы одна КОЛОНИЯ в
 * системе (с жителями: застава звезду не держит — она ставится одним кораблём и ничего не
 * стоит), а чужих планет — ни колоний, ни застав — там нет вовсе. Любая чужая планета у
 * звезды обрывает счёт: звезда оспорена, и её надо очистить снова. Так соседу есть что
 * делать, кроме как ждать двадцать ходов, — высадить колонию или заставу рядом.
 * <p>
 * <b>Счёт непрерывный.</b> Потерял звезду хоть на ход — двадцать ходов начинаются заново.
 * Двадцать — решение хозяина проекта: короче, и звезду забирают одним броском флота, не
 * успев встретить ответ; длиннее, и в малой галактике партия до этого не доживает.
 */
public final class WardenholdRules {

    /** Сколько ходов подряд нужно держать звезду, чтобы победить. */
    public static final int HOLD_TURNS = 20;

    /**
     * Как часто напоминать галактике, что звезду держат: раз в столько ходов и в последние
     * ходы перед победой. Каждый ход — это строка в отчёте каждого игрока двадцать ходов
     * подряд, а не сказать вовсе — значит проиграть, не узнав, что игра шла к концу.
     */
    public static final int REMIND_EVERY = 5;

    private WardenholdRules() {
    }

    /**
     * Кто держит особую звезду: единственный хозяин планет её системы, у которого там есть
     * колония с жителями. Пусто — звезду не держит никто: она свободна, оспорена или у
     * хозяина там одни заставы.
     */
    public static UUID holder(Collection<PlanetEntity> planets) {
        UUID owner = null;
        boolean colony = false;
        for (PlanetEntity planet : planets) {
            UUID planetOwner = planet.getOwnerPlayerId();
            if (planetOwner == null) {
                continue;
            }
            if (owner != null && !owner.equals(planetOwner)) {
                return null;
            }
            owner = planetOwner;
            if (planet.getPopulation() != null && planet.getPopulation() > 0) {
                colony = true;
            }
        }
        return colony ? owner : null;
    }

    /**
     * Сколько ходов держатель держит звезду, считая текущий: держит с хода 10 — на ходу 10
     * это первый ход, на ходу 29 — двадцатый.
     */
    public static Integer heldTurns(Integer sinceTurn, Integer turn) {
        return turn - sinceTurn + 1;
    }

    /** Сколько ходов осталось держать до победы; ноль — победа в этом ходу. */
    public static Integer turnsLeft(Integer sinceTurn, Integer turn) {
        return Math.max(0, HOLD_TURNS - heldTurns(sinceTurn, turn));
    }

    /** Сколько ходов осталось держателю партии; пусто — звезду никто не держит. */
    public static Integer turnsLeft(GameEntity game) {
        if (!Boolean.TRUE.equals(game.getWardenholdVictory())
                || game.getWardenholdHolderPlayerId() == null
                || game.getWardenholdSinceTurn() == null) {
            return null;
        }
        // Счёт записан на конец хода, а номер партии уже следующий: «осталось» считается
        // от последнего посчитанного хода, иначе экран забегал бы на ход вперёд.
        return turnsLeft(game.getWardenholdSinceTurn(), Math.max(game.getWardenholdSinceTurn(), game.getTurn() - 1));
    }

    /** Напоминать ли галактике о держателе на этом ходу удержания. */
    public static Boolean remind(Integer heldTurns) {
        int left = HOLD_TURNS - heldTurns;
        return heldTurns == 1 || left <= 3 || heldTurns % REMIND_EVERY == 0;
    }
}
