package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.SpaceBattleEntity;
import com.moo3.server.domain.enums.BattleState;
import com.moo3.server.dto.BattleActionRequest;
import com.moo3.server.dto.BattleDto;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.web.error.ForbiddenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Тактический бой со стороны контроллера — п. 8.
 * <p>
 * Тонкая прослойка, как {@code PlanetService} у колоний: проверяет доступ к партии,
 * собирает участников и названия систем и отдаёт сцене готовый ответ. Сами правила боя
 * живут в {@link TacticalBattleService}, а закрытие встречи после боя — в
 * {@link EncounterService}: бой родился из встречи, ей же и отчитывается.
 */
@Service
public class BattleService {

    private final GameAccess gameAccess;
    private final TacticalBattleService battles;
    private final EncounterService encounters;
    private final MonsterBattleService monsterBattles;
    private final FleetService fleetService;
    private final PlayerRepository playerRepository;

    public BattleService(GameAccess gameAccess,
                         TacticalBattleService battles,
                         EncounterService encounters,
                         MonsterBattleService monsterBattles,
                         FleetService fleetService,
                         PlayerRepository playerRepository) {
        this.gameAccess = gameAccess;
        this.battles = battles;
        this.encounters = encounters;
        this.monsterBattles = monsterBattles;
        this.fleetService = fleetService;
        this.playerRepository = playerRepository;
    }

    /** Бои партии, которые ещё идут и в которых участвует этот игрок. */
    @Transactional(readOnly = true)
    public List<BattleDto> active(UUID gameId, String accessToken) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        Map<UUID, PlayerEntity> players = players(gameId);
        Map<UUID, String> systems = fleetService.systemNames(gameId);

        return battles.active(gameId).stream()
                .filter(battle -> involves(battle, player.getId()))
                .map(battle -> battles.toDto(battles.view(battle.getId()), player, players,
                        systems.getOrDefault(battle.getStarSystemId(), "неизвестная система")))
                .toList();
    }

    /** Состояние поля боя — п. 8. Смотреть чужой бой нельзя: это разведка даром. */
    @Transactional(readOnly = true)
    public BattleDto battle(UUID gameId, UUID battleId, String accessToken) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        SpaceBattleEntity battle = battles.require(battleId);
        requireParticipant(battle, player);

        return battles.toDto(battles.view(battleId), player, players(gameId), systemName(gameId, battle));
    }

    /**
     * Ход корабля — п. 8.
     * <p>
     * После хода бой мог кончиться: тогда встреча закрывается его исходом, а потери уже
     * списаны с флотов. Закрывать встречу здесь, а не в самом бою, нужно затем, чтобы бой
     * не знал ничего про дипломатию и итоги хода — он считает поле, и только.
     * <p>
     * У боя с чудищем встречи нет: исход там переводится обратно в силу сторожа
     * ({@code MonsterBattleService.close}) — п. 11.1.
     */
    @Transactional
    public BattleDto act(UUID gameId, UUID battleId, String accessToken, BattleActionRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, accessToken);
        SpaceBattleEntity battle = battles.require(battleId);
        requireParticipant(battle, player);

        Map<UUID, PlayerEntity> players = players(gameId);
        TacticalBattleService.BattleView view = battles.act(player, battleId, request, players);

        if (view.battle().getState() == BattleState.FINISHED) {
            // Бой с чудищем отчитывается не встрече, а СИСТЕМЕ: сторожа там либо больше
            // нет, либо он остался раненым — п. 11.1. Встречи у такого боя не бывает
            // вовсе, и закрывать ей нечего.
            if (Boolean.TRUE.equals(monsterBattles.isMonsterBattle(view.battle()))) {
                monsterBattles.close(view.battle(), game.getTurn());
            } else {
                encounters.closeByBattle(view.battle(), players, game.getTurn());
            }
        }

        return battles.toDto(view, player, players, systemName(gameId, battle));
    }

    private void requireParticipant(SpaceBattleEntity battle, PlayerEntity player) {
        if (!involves(battle, player.getId())) {
            throw new ForbiddenException("battle.notYours");
        }
    }

    private Boolean involves(SpaceBattleEntity battle, UUID playerId) {
        return battle.getAttackerPlayerId().equals(playerId)
                || battle.getDefenderPlayerId().equals(playerId);
    }

    private String systemName(UUID gameId, SpaceBattleEntity battle) {
        return fleetService.systemNames(gameId)
                .getOrDefault(battle.getStarSystemId(), "неизвестная система");
    }

    private Map<UUID, PlayerEntity> players(UUID gameId) {
        return fleetService.playersById(playerRepository.findAllByGameIdOrderBySlotAsc(gameId));
    }
}
