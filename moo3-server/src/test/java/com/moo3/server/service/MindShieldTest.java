package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.entity.PlayerLeaderEntity;
import com.moo3.server.domain.enums.LeaderState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Мысленный щит: лидер-телепат не даёт подчинить колонию, где служит, — п. 6, п. 7.
 * <p>
 * Правило оригинала («leaders with Telepath skills protect colonies from mind control»,
 * руководство патча 1.50) — единственное противоядие от подчинения, и без него сторона
 * расы «телепаты» мерилась шестьюдесятью двумя очками при бюджете двадцать (журнал,
 * п. 3.92). Ошибка здесь не падает и не видна на экране: щит просто перестаёт держать, а
 * заметно это будет лишь через прогон в пятьсот партий.
 * <p>
 * Проверка идёт по строкам службы, без базы, — для того у {@code guardsSystem} и есть
 * второй вид, как у {@code from}. Справочник читается тот самый, который уходит в игру:
 * опечатка в способности ломает сборку, а не партию.
 */
class MindShieldTest {

    private static final String SHIELD = "TELEPATH";

    private final LeaderCatalog catalog = new LeaderCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    private final LeaderBonusService bonuses =
            new LeaderBonusService(catalog, new LeaderRules(), null);

    private final UUID home = UUID.randomUUID();
    private final UUID abroad = UUID.randomUUID();

    /**
     * Носителя щита берём из самого справочника, а не по имени: правка состава лидеров не
     * должна ломать проверку молча — она должна её ронять, если носителей не осталось
     * вовсе.
     */
    private String telepathCode() {
        return catalog.all().stream()
                .filter(leader -> leader.skills().stream()
                        .anyMatch(skill -> SHIELD.equals(skill.ability())))
                .map(com.moo3.server.domain.Leader::code)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "в справочнике не осталось ни одного лидера со способностью " + SHIELD));
    }

    private PlayerLeaderEntity serving(String code, LeaderState state, UUID systemId,
                                       Integer arrivesTurn) {
        PlayerLeaderEntity row = new PlayerLeaderEntity();
        row.setLeaderCode(code);
        row.setState(state);
        row.setStarSystemId(systemId);
        row.setArrivesTurn(arrivesTurn);
        return row;
    }

    @Test
    @DisplayName("Телепат на службе закрывает свою систему — и только свою")
    void guardsOnlyItsOwnSystem() {
        List<PlayerLeaderEntity> rows =
                List.of(serving(telepathCode(), LeaderState.HIRED, home, 10));
        assertTrue(bonuses.guardsSystem(rows, home, SHIELD));
        assertFalse(bonuses.guardsSystem(rows, abroad, SHIELD),
                "щит не должен растекаться на всю империю: его держит тот, кто сидит на колонии");
    }

    @Test
    @DisplayName("Лидер в пути колонию ещё не охраняет")
    void leaderOnTheWayDoesNotGuard() {
        List<PlayerLeaderEntity> rows =
                List.of(serving(telepathCode(), LeaderState.HIRED, home, null));
        assertFalse(bonuses.guardsSystem(rows, home, SHIELD));
    }

    @Test
    @DisplayName("Предложенный, но не нанятый лидер не охраняет ничего")
    void offeredLeaderDoesNotGuard() {
        List<PlayerLeaderEntity> rows =
                List.of(serving(telepathCode(), LeaderState.OFFERED, home, 10));
        assertFalse(bonuses.guardsSystem(rows, home, SHIELD));
    }

    @Test
    @DisplayName("Лидер без этой способности колонию не закрывает")
    void otherLeaderDoesNotGuard() {
        String plain = catalog.all().stream()
                .filter(leader -> leader.skills().stream()
                        .noneMatch(skill -> SHIELD.equals(skill.ability())))
                .map(com.moo3.server.domain.Leader::code)
                .findFirst()
                .orElseThrow();
        List<PlayerLeaderEntity> rows = List.of(serving(plain, LeaderState.HIRED, home, 10));
        assertFalse(bonuses.guardsSystem(rows, home, SHIELD));
    }

    @Test
    @DisplayName("Носители щита в справочнике есть, и все они колониальные")
    void shieldCarriersAreColonyLeaders() {
        List<com.moo3.server.domain.Leader> carriers = catalog.all().stream()
                .filter(leader -> leader.skills().stream()
                        .anyMatch(skill -> SHIELD.equals(skill.ability())))
                .toList();
        assertFalse(carriers.isEmpty(), "щит без носителей — это правило, которого нет в игре");
        // Корабельному лидеру система не назначается вовсе, и щит от него не получился бы
        // ни при каких условиях: пусть это ловится здесь, а не в партии.
        assertTrue(carriers.stream().allMatch(
                        leader -> leader.kind() == com.moo3.server.domain.enums.LeaderKind.COLONY),
                "щит держит колониальный лидер: " + carriers);
    }
}
