package com.moo3.server.web;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.dto.EncounterDecisionRequest;
import com.moo3.server.dto.EncounterDto;
import com.moo3.server.dto.FleetGroupDto;
import com.moo3.server.dto.FleetLandingRequest;
import com.moo3.server.dto.FleetMoveRequest;
import com.moo3.server.dto.FleetScrapRequest;
import com.moo3.server.dto.InvasionResultDto;
import com.moo3.server.dto.StarSystemDto;
import com.moo3.server.service.EncounterService;
import com.moo3.server.service.FleetService;
import com.moo3.server.service.GameAccess;
import com.moo3.server.service.PlanetService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Флоты и встречи флотов — п. 8.
 * <p>
 * Флот перелетает между известными игроку системами; встретив чужой, стороны по очереди
 * решают, драться или разойтись. Сам бой считает заглушка — см. {@code SpaceBattleService}.
 */
@RestController
@RequestMapping("/api/games/{gameId}")
@Validated
public class FleetController {

    private final GameAccess gameAccess;
    private final FleetService fleetService;
    private final EncounterService encounterService;
    private final PlanetService planetService;

    public FleetController(GameAccess gameAccess,
                           FleetService fleetService,
                           EncounterService encounterService,
                           PlanetService planetService) {
        this.gameAccess = gameAccess;
        this.fleetService = fleetService;
        this.encounterService = encounterService;
        this.planetService = planetService;
    }

    /** Флоты игрока по системам — п. 8. */
    @GetMapping("/fleets")
    public List<FleetGroupDto> fleets(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return fleetService.fleetsOf(gameAccess.requirePlayerOfRunningGame(gameId, accessToken));
    }

    /**
     * Перелёт флота — п. 8: к любой звезде партии, в том числе неразведанной. Приход
     * в неизвестную систему её и разведывает — п. 15.
     * <p>
     * Лететь может весь флот или отобранная игроком часть: список кораблей в запросе
     * необязателен, и пустой означает «весь флот».
     */
    @PostMapping("/fleets/{fleetId}/move")
    public FleetGroupDto move(@PathVariable UUID gameId,
                              @PathVariable UUID fleetId,
                              @Valid @RequestBody FleetMoveRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        return fleetService.move(
                game,
                gameAccess.requirePlayer(game, request.accessToken()),
                fleetId,
                request.targetSystemId(),
                request.shipsOrEmpty());
    }

    /**
     * Списание кораблей — п. 8: экран флота и есть то место, откуда корабль убирают
     * из строя, как в MOO II. Возврата за списанное нет.
     */
    @PostMapping("/fleets/{fleetId}/scrap")
    public List<FleetGroupDto> scrap(@PathVariable UUID gameId,
                                     @PathVariable UUID fleetId,
                                     @Valid @RequestBody FleetScrapRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        return fleetService.scrap(gameAccess.requirePlayer(game, request.accessToken()),
                fleetId, request.ships());
    }

    /**
     * Основать колонию колониальным кораблём флота — п. 4.1.
     * <p>
     * Тем расселение и выходит за пределы родной системы: колониальная база селится
     * только по соседству, а корабль — куда долетел.
     */
    @PostMapping("/fleets/{fleetId}/colonize")
    public StarSystemDto colonize(@PathVariable UUID gameId,
                                  @PathVariable UUID fleetId,
                                  @Valid @RequestBody FleetLandingRequest request) {
        return planetService.colonizeFromFleet(gameId, fleetId, request);
    }

    /** Поставить заставу кораблём-заставой флота — п. 8: дальность империи растёт. */
    @PostMapping("/fleets/{fleetId}/outpost")
    public StarSystemDto outpost(@PathVariable UUID gameId,
                                 @PathVariable UUID fleetId,
                                 @Valid @RequestBody FleetLandingRequest request) {
        return planetService.outpostFromFleet(gameId, fleetId, request);
    }

    /**
     * Высадить десант с транспортов флота — п. 12: захват чужой колонии в той системе,
     * где стоит флот.
     */
    @PostMapping("/fleets/{fleetId}/invade")
    public InvasionResultDto invade(@PathVariable UUID gameId,
                                    @PathVariable UUID fleetId,
                                    @Valid @RequestBody FleetLandingRequest request) {
        return planetService.invadeFromFleet(gameId, fleetId, request);
    }

    /**
     * Подчинить чужую колонию телепатией — п. 7, п. 12: вместо десанта, если во флоте
     * есть корабль не меньше крейсера.
     */
    @PostMapping("/fleets/{fleetId}/mind-control")
    public StarSystemDto mindControl(@PathVariable UUID gameId,
                                     @PathVariable UUID fleetId,
                                     @Valid @RequestBody FleetLandingRequest request) {
        return planetService.mindControlFromFleet(gameId, fleetId, request);
    }

    /** Встречи флотов, ждущие решения, — п. 8. */
    @GetMapping("/encounters")
    public List<EncounterDto> encounters(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return encounterService.pending(gameAccess.requirePlayerOfRunningGame(gameId, accessToken));
    }

    /**
     * Решение при встрече — п. 8: атаковать или разойтись. Признак «авто» включён по
     * умолчанию: ручной бой пока заглушка.
     */
    @PostMapping("/encounters/{encounterId}")
    public EncounterDto decide(@PathVariable UUID gameId,
                               @PathVariable UUID encounterId,
                               @Valid @RequestBody EncounterDecisionRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        return encounterService.decide(
                gameAccess.requirePlayer(game, request.accessToken()),
                game.getTurn(),
                encounterId,
                request.decision(),
                request.autoOrDefault());
    }
}
