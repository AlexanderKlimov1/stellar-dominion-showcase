package com.moo3.server.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Что колония выставляет в бой — п. 8, п. 11: орбитальные платформы и наземная оборона.
 * <p>
 * <b>Орбита — лестница, а не набор.</b> В MOO II боевая станция ЗАМЕНЯЕТ звёздную базу, а
 * звёздная крепость — и базу, и станцию: «Battlestations replace Star Bases and Star
 * Fortresses replace Star Bases and Battlestations». Поэтому на орбите колонии всегда
 * ровно одна платформа — лучшая из построенных, — а не три сразу. Здания при этом с
 * планеты никуда не деваются: игрок волен построить станцию, не снося базы, и платит
 * содержание за обе — это его дело, а в бой всё равно выйдет станция.
 * <p>
 * <b>Планета — набор, а не лестница.</b> Ракетные шахты, наземные батареи и ангары
 * истребителей бьют с поверхности каждое само по себе и друг друга не заменяют: в
 * оригинале это три разных здания планетарной обороны. Каждое выставляет свою батарею.
 * <p>
 * <b>Чем платформа вооружена, здесь не решается.</b> Корпус ей даёт справочник кораблей
 * ({@code resources/Ships/ship-components.json}, признак {@code platform}), а оружие,
 * броню и приборы подбирает {@link ShipDesignService#ensurePlatformDesigns} — тем же
 * способом, каким игра держит в порядке шесть ячеек дизайна. Оттого платформа и
 * «улучшается сама»: изучил лучшую пушку — она уже стоит на станции, и отдельной стройки
 * для этого не нужно, как и в MOO II.
 */
@Service
public class OrbitalDefenceRules {

    /** Здание звёздной базы — оно же верфь колонии (п. 8). */
    public static final String STAR_BASE = "star-base";

    /** Здание боевой станции: заменяет базу на орбите. */
    public static final String BATTLE_STATION = "battle-station";

    /** Здание звёздной крепости: заменяет и базу, и станцию. */
    public static final String STAR_FORTRESS = "star-fortress";

    /**
     * Лестница орбиты от худшей платформы к лучшей.
     * <p>
     * Порядок здесь и есть правило замены: берётся последняя построенная из списка.
     */
    private static final List<String> ORBIT = List.of(STAR_BASE, BATTLE_STATION, STAR_FORTRESS);

    /**
     * Здания планетарной обороны и корпус, которым каждое выходит в бой.
     * <p>
     * Корпус у всех трёх один: разница между ракетной шахтой, батареей и ангаром в
     * оригинале — в том, чем именно они стреляют, а здесь оружие платформе подбирается по
     * изученному, и своего у них не остаётся. Поэтому честнее считать их тремя одинаковыми
     * батареями, чем выдумывать каждой своё вооружение, которого в справочнике нет.
     */
    private static final List<String> SURFACE =
            List.of("missile-base", "ground-batteries", "fighter-garrison");

    /** Корпус наземной батареи — код из справочника кораблей. */
    public static final String SURFACE_HULL = "planetary-battery";

    /** Здание планетарного щита: держит огонь на себе, но десанта не останавливает. */
    public static final String FLUX_SHIELD = "planetary-flux-shield";

    /**
     * Здание планетарного барьера — п. 11, п. 12: пока оно стоит, десанта не будет вовсе.
     * <p>
     * Правило оригинала целиком: «As long as the barrier shield is in place, neither ground
     * Marines nor biological weapons can enter the planet's atmosphere». Сбивается барьер
     * в бою, как и всякая платформа, — и только после этого колонию можно брать.
     */
    public static final String BARRIER_SHIELD = "planetary-barrier-shield";

    /** Корпус планетарного щита — общий у обоих: они отличаются не полем, а тем, что держат. */
    public static final String SHIELD_HULL = "planetary-shield";

    /** Оба щита в порядке от слабого к сильному: в бой выходит лучший, как и на орбите. */
    private static final List<String> SHIELDS = List.of(FLUX_SHIELD, BARRIER_SHIELD);

    /**
     * Сеть Артемиды — п. 11: минное поле вокруг всей системы.
     * <p>
     * Не платформа: в бою её нет вовсе, она бьёт раньше — на подлёте
     * ({@code ArrivalPhase}). «A gigantic spherical network of high-yield mines that
     * surrounds an entire planetary system».
     */
    public static final String ARTEMIS_NET = "artemis-system-net";

    /** Все корпуса платформ, какие бывают: по ним заводятся проекты империи. */
    public static final List<String> HULLS =
            List.of(STAR_BASE, BATTLE_STATION, STAR_FORTRESS, SURFACE_HULL, SHIELD_HULL);

    /**
     * Что выставит в бой колония с такими постройками — п. 8, п. 11.
     * <p>
     * Возвращает коды корпусов: одна орбитальная платформа (лучшая из построенных) плюс по
     * батарее за каждое здание планетарной обороны. Пустой список — колония беззащитна, и
     * боя из-за неё не будет вовсе.
     *
     * @param built коды зданий, стоящих на планете
     */
    public List<String> platformsOf(Collection<String> built) {
        List<String> platforms = new ArrayList<>(1 + SURFACE.size());

        String best = null;
        for (String code : ORBIT) {
            if (built.contains(code)) {
                best = code;
            }
        }
        if (best != null) {
            platforms.add(best);
        }

        for (String code : SURFACE) {
            if (built.contains(code)) {
                platforms.add(SURFACE_HULL);
            }
        }

        // Щит один, как и платформа на орбите: барьер сильнее простого поля и заменяет его
        // собой — стоять двумя полями вокруг одной планеты незачем.
        if (SHIELDS.stream().anyMatch(built::contains)) {
            platforms.add(SHIELD_HULL);
        }
        return platforms;
    }

    /**
     * Прикрыт ли десант планетарным барьером — п. 11, п. 12.
     * <p>
     * Пока барьер стоит, высадка невозможна ни для кого: правило оригинала прямое. Простое
     * поле ({@link #FLUX_SHIELD}) десанта не держит — оно про огонь с орбиты.
     */
    public Boolean blocksLanding(Collection<String> built) {
        return built.contains(BARRIER_SHIELD);
    }

    /**
     * Какое здание погибло вместе с платформой — п. 8.
     * <p>
     * Обратное отображение: платформу в бою уничтожают, и на планете должно исчезнуть то
     * самое здание, которое её выставило. Для наземных батарей возвращается пусто — какая
     * именно из трёх погибла, бой не различает, и выбирает это вызывающий.
     */
    public String buildingOf(String hull) {
        return ORBIT.contains(hull) ? hull : null;
    }

    /** Здания планетарной обороны в порядке, в каком они выставляют батареи. */
    public List<String> surfaceBuildings() {
        return SURFACE;
    }

    /**
     * Щиты в порядке от слабого к сильному.
     * <p>
     * Разбитый щит уносит СЛАБЕЙШЕЕ из построенных полей: барьер снимается последним, и
     * колония с обоими щитами теряет сперва простой, а с ним и всю свою неприступность —
     * не сразу.
     */
    public List<String> shieldBuildings() {
        return SHIELDS;
    }

    /**
     * Служебная ячейка проекта под этот корпус — п. 8.
     * <p>
     * Ячейки платформ ОТРИЦАТЕЛЬНЫЕ: шесть ячеек дизайна заняты игроком, нулевая —
     * гражданскими кораблями ({@link ShipDesignService#CIVIL_SLOT}), а окно дизайна
     * показывает ячейки с первой. Так платформа не отнимает у игрока места и не
     * показывается там, где её нельзя переделать.
     */
    public Integer slotOf(String hull) {
        Map<String, Integer> slots = Map.of(
                STAR_BASE, -1,
                BATTLE_STATION, -2,
                STAR_FORTRESS, -3,
                SURFACE_HULL, -4,
                SHIELD_HULL, -5);
        Integer slot = slots.get(hull);
        if (slot == null) {
            throw new IllegalArgumentException("Не платформа обороны: " + hull);
        }
        return slot;
    }
}
