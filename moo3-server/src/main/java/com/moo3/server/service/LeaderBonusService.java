package com.moo3.server.service;

import com.moo3.server.domain.Leader;
import com.moo3.server.domain.entity.PlayerLeaderEntity;
import com.moo3.server.domain.enums.LeaderState;
import com.moo3.server.repository.PlayerLeaderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Прибавки от лидеров — п. 6: что именно они дают империи, системам и флотам.
 * <p>
 * <b>Почему отдельно от {@link LeaderService}.</b> Прибавки спрашивают колонии, флот и
 * исследования, а сам {@code LeaderService} умеет нанимать — и ради технологий, которые
 * лидер приносит с собой, зависит от исследований. Получалось кольцо: колонии → лидеры →
 * исследования → колонии, и Spring такой контекст не поднимает вовсе. Здесь только чтение
 * справочника и базы, поэтому зависеть от этой службы может кто угодно.
 * <p>
 * Способности делятся по месту действия: помеченные в справочнике {@code works: ALWAYS}
 * работают, где бы лидер ни находился, остальные — только там, где он служит, и только
 * после того, как добрался (в оригинале дорога занимает пять ходов).
 */
@Service
public class LeaderBonusService {

    private final LeaderCatalog catalog;
    private final LeaderRules rules;
    private final PlayerLeaderRepository leaders;

    public LeaderBonusService(LeaderCatalog catalog, LeaderRules rules,
                              PlayerLeaderRepository leaders) {
        this.catalog = catalog;
        this.rules = rules;
        this.leaders = leaders;
    }

    /** Прибавки одного игрока. */
    @Transactional(readOnly = true)
    public Bonuses of(UUID playerId) {
        return from(leaders.findAllByPlayerId(playerId));
    }

    /**
     * Прибавки сразу нескольких игроков — одной выборкой.
     * <p>
     * Так их берут колонии: контекст собирается на весь набор планет, и владельцев в нём
     * столько же, сколько империй в партии. Выборка «на игрока» внутри посчитанного хода
     * обходится дороже, чем кажется, — см. «Грабли» в CLAUDE.md.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Bonuses> of(Collection<UUID> playerIds) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<PlayerLeaderEntity>> byPlayer = new HashMap<>();
        leaders.findAllByPlayerIdIn(playerIds).forEach(row ->
                byPlayer.computeIfAbsent(row.getPlayerId(), id -> new java.util.ArrayList<>()).add(row));

        Map<UUID, Bonuses> result = new HashMap<>(byPlayer.size());
        byPlayer.forEach((playerId, rows) -> result.put(playerId, from(rows)));
        return result;
    }

    /**
     * Служит ли в этой системе лидер с названной способностью — п. 6, п. 7.
     * <p>
     * Отвечает ФАКТОМ, а не числом, и потому спрашивается мимо {@link Bonuses}: там
     * способности со {@code works: ALWAYS} сложены по всей империи. Проценту к защите от
     * шпионов это и нужно — контрразведка в игре общая, — а мысленному щиту нет: щит
     * держит тот, кто сидит на самой колонии. В MOO II лидер-телепат оберегает от
     * подчинения ту колонию, где служит (руководство патча 1.50), и все четыре носителя
     * {@code TELEPATH} в справочнике колониальные — корабельному система не назначается
     * вовсе, и под это условие он не подпадёт.
     * <p>
     * Считается только добравшийся до места службы ({@code arrivesTurn}): лидер в пути
     * колонию не охраняет — то же правило, что у прибавок по системам.
     */
    @Transactional(readOnly = true)
    public Boolean guardsSystem(UUID playerId, UUID systemId, String ability) {
        return guardsSystem(leaders.findAllByPlayerId(playerId), systemId, ability);
    }

    /**
     * То же по уже прочитанным строкам службы — и то же, ради чего открыт {@link #from}:
     * так правило проверяется тестом, без базы.
     */
    public Boolean guardsSystem(List<PlayerLeaderEntity> rows, UUID systemId, String ability) {
        for (PlayerLeaderEntity row : rows) {
            if (row.getState() != LeaderState.HIRED
                    || row.getArrivesTurn() == null
                    || !systemId.equals(row.getStarSystemId())) {
                continue;
            }
            Leader leader = catalog.require(row.getLeaderCode());
            if (leader.skills().stream().anyMatch(skill -> ability.equals(skill.ability()))) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    /** Прибавки по уже прочитанным строкам службы. */
    public Bonuses from(List<PlayerLeaderEntity> rows) {
        Map<String, Integer> empire = new HashMap<>();
        Map<UUID, Map<String, Integer>> bySystem = new HashMap<>();
        Map<UUID, Map<String, Integer>> byFleet = new HashMap<>();

        for (PlayerLeaderEntity row : rows) {
            if (row.getState() != LeaderState.HIRED) {
                continue;
            }
            Leader leader = catalog.require(row.getLeaderCode());
            Integer rankIndex = catalog.rankIndex(leader.kind(), row.getExperience());
            Integer startIndex = catalog.rankIndex(leader.kind(), leader.startExperience());

            for (Leader.LeaderSkill skill : leader.skills()) {
                LeaderCatalog.Ability ability = catalog.ability(skill.ability());
                if (ability == null || "NONE".equals(ability.effect())) {
                    // Способности, которой в игре пока нечему влиять, в прибавках нет:
                    // она видна игроку в описании лидера и там же объяснена.
                    continue;
                }
                Integer value = rules.skillValue(skill, rankIndex, startIndex);
                if ("ALWAYS".equals(ability.works())) {
                    empire.merge(skill.ability(), value, Integer::sum);
                    continue;
                }
                if (row.getArrivesTurn() == null) {
                    continue;
                }
                if (row.getStarSystemId() != null) {
                    bySystem.computeIfAbsent(row.getStarSystemId(), id -> new HashMap<>())
                            .merge(skill.ability(), value, Integer::sum);
                }
                if (row.getFleetId() != null) {
                    byFleet.computeIfAbsent(row.getFleetId(), id -> new HashMap<>())
                            .merge(skill.ability(), value, Integer::sum);
                }
            }
        }
        return new Bonuses(empire, bySystem, byFleet);
    }

    /**
     * Снимок прибавок: по империи, по системам и по флотам.
     *
     * @param empire   способности, работающие всегда, — сложены по всей империи
     * @param bySystem колониальные способности систем, где служат лидеры
     * @param byFleet  корабельные способности флотов, где служат лидеры
     */
    public record Bonuses(Map<String, Integer> empire,
                          Map<UUID, Map<String, Integer>> bySystem,
                          Map<UUID, Map<String, Integer>> byFleet) {

        /** Прибавка по всей империи; ноль — такой способности ни у кого нет. */
        public Integer empireValue(String ability) {
            return empire.getOrDefault(ability, 0);
        }

        /** Прибавка колониальной способности в этой системе. */
        public Integer systemValue(UUID systemId, String ability) {
            return bySystem.getOrDefault(systemId, Map.of()).getOrDefault(ability, 0);
        }

        /** Прибавка корабельной способности этому флоту. */
        public Integer fleetValue(UUID fleetId, String ability) {
            return byFleet.getOrDefault(fleetId, Map.of()).getOrDefault(ability, 0);
        }

        /** Пустой снимок: лидеров нет. */
        public static Bonuses empty() {
            return new Bonuses(Map.of(), Map.of(), Map.of());
        }
    }
}
