package com.moo3.server.web;

import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.dto.AssignLeaderRequest;
import com.moo3.server.dto.LeadersDto;
import com.moo3.server.repository.PlanetRepository;
import com.moo3.server.service.GameAccess;
import com.moo3.server.service.LeaderService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Лидеры империи — п. 6: офицерский резерв, наём, отказ и назначение.
 * <p>
 * Действия над партией живут в своих службах, а не в {@code GameService}: здесь это
 * {@code LeaderService}. Пропуск игрока идёт заголовком, как и везде.
 */
@RestController
@RequestMapping("/api/games/{gameId}/leaders")
@Validated
public class LeaderController {

    private final LeaderService leaderService;
    private final GameAccess gameAccess;
    private final PlanetRepository planets;

    public LeaderController(LeaderService leaderService, GameAccess gameAccess,
                            PlanetRepository planets) {
        this.leaderService = leaderService;
        this.gameAccess = gameAccess;
        this.planets = planets;
    }

    /** Офицерский резерв: кто предлагает службу, кто служит и сколько мест занято. */
    @GetMapping
    public LeadersDto leaders(@PathVariable UUID gameId, @AccessToken String accessToken) {
        PlayerEntity player = gameAccess.requirePlayer(gameAccess.requireGame(gameId), accessToken);
        return leaderService.state(player, gameAccess.requireGame(gameId).getTurn());
    }

    /**
     * Нанять — п. 6: разовая плата списывается сразу, технологии лидера достаются империи
     * тут же, до всякого назначения.
     */
    @PostMapping("/{leaderId}/hire")
    public LeadersDto hire(@PathVariable UUID gameId, @PathVariable UUID leaderId,
                           @AccessToken String accessToken) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        Integer turn = gameAccess.requireGame(gameId).getTurn();
        leaderService.hire(player, leaderId, turn);
        return leaderService.state(player, turn);
    }

    /** Отказать предложившему или уволить служащего — п. 6. */
    @PostMapping("/{leaderId}/dismiss")
    public LeadersDto dismiss(@PathVariable UUID gameId, @PathVariable UUID leaderId,
                              @AccessToken String accessToken) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        leaderService.dismiss(player, leaderId);
        return leaderService.state(player, gameAccess.requireGame(gameId).getTurn());
    }

    /**
     * Назначить: колониального — в звёздную систему, корабельного — во флот (п. 6).
     * Пустая цель снимает назначение и возвращает лидера в резерв.
     */
    @PostMapping("/{leaderId}/assign")
    public LeadersDto assign(@PathVariable UUID gameId, @PathVariable UUID leaderId,
                             @Valid @RequestBody AssignLeaderRequest request,
                             @AccessToken String accessToken) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        Integer turn = gameAccess.requireGame(gameId).getTurn();
        leaderService.assign(player, leaderId, request.targetId(), turn, homeSystem(player));
        return leaderService.state(player, turn);
    }

    /**
     * Система офицерского резерва — родная: назначенный туда лидер приступает сразу, а в
     * прочие добирается пять ходов (п. 6).
     */
    private UUID homeSystem(PlayerEntity player) {
        return planets.findAllByOwnerPlayerId(player.getId()).stream()
                .filter(planet -> Boolean.TRUE.equals(planet.getHomeworld()))
                .findFirst()
                .map(PlanetEntity::getStarSystem)
                .map(system -> system.getId())
                .orElse(null);
    }
}
