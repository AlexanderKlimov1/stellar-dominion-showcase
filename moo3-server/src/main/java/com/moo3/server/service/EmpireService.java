package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.dto.DiplomacyActionRequest;
import com.moo3.server.dto.DiplomacyRelationDto;
import com.moo3.server.dto.TechTradeDto;
import com.moo3.server.dto.AssignSpyRequest;
import com.moo3.server.dto.EspionageDto;
import com.moo3.server.dto.FleetDto;
import com.moo3.server.dto.SpyDto;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Дела империи целиком, а не отдельной планеты: разведка (п. 13) и флот (п. 8).
 * <p>
 * Тонкая прослойка между контроллером и подсистемами: проверяет доступ к партии и
 * спрашивает {@link EspionageService} и {@link ShipService}. Сами правила живут там.
 */
@Service
public class EmpireService {

    private final GameAccess gameAccess;
    private final EspionageService espionageService;
    private final ShipService shipService;
    private final ExplorationService explorationService;
    private final DiplomacyService diplomacyService;
    private final ColonyService colonyService;
    private final GameMapper gameMapper;

    public EmpireService(GameAccess gameAccess,
                         EspionageService espionageService,
                         ShipService shipService,
                         ExplorationService explorationService,
                         DiplomacyService diplomacyService,
                         ColonyService colonyService,
                         GameMapper gameMapper) {
        this.gameAccess = gameAccess;
        this.espionageService = espionageService;
        this.shipService = shipService;
        this.explorationService = explorationService;
        this.diplomacyService = diplomacyService;
        this.colonyService = colonyService;
        this.gameMapper = gameMapper;
    }

    /** Знакомые империи и отношения с ними — п. 15. */
    @Transactional(readOnly = true)
    public List<DiplomacyRelationDto> relations(UUID gameId, String accessToken) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        return diplomacyService.relations(player);
    }

    /** Что можно обменять с этой империей — п. 15: чем поделиться и что попросить. */
    @Transactional(readOnly = true)
    public TechTradeDto tradeable(UUID gameId, String accessToken, UUID otherPlayerId) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        return diplomacyService.tradeableWith(player, otherPlayerId);
    }

    /** Предложить мир или объявить войну — п. 15. */
    @Transactional
    public DiplomacyRelationDto diplomacy(UUID gameId, DiplomacyActionRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, request.accessToken());
        return diplomacyService.act(player, request, game.getTurn());
    }

    @Transactional(readOnly = true)
    public EspionageDto espionage(UUID gameId, String accessToken) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        return espionageService.state(player);
    }

    /** Отправить шпиона к сопернику или отозвать домой — п. 13. */
    @Transactional
    public SpyDto assignSpy(UUID gameId, AssignSpyRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, request.accessToken());
        return espionageService.assign(player, request, game.getTurn());
    }

    @Transactional(readOnly = true)
    public FleetDto fleet(UUID gameId, String accessToken) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        return shipService.fleet(game, gameAccess.requirePlayer(game, accessToken));
    }
}
