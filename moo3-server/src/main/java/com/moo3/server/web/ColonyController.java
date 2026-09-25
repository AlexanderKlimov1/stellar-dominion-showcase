package com.moo3.server.web;

import com.moo3.server.dto.ColonizeRequest;
import com.moo3.server.dto.InvadeRequest;
import com.moo3.server.dto.InvasionResultDto;
import com.moo3.server.dto.MoveQueueRequest;
import com.moo3.server.dto.PlanetDto;
import com.moo3.server.dto.StarSystemDto;
import com.moo3.server.dto.SellBuildingRequest;
import com.moo3.server.dto.SetPopulationRequest;
import com.moo3.server.dto.SetProjectRequest;
import com.moo3.server.dto.TransferPopulationRequest;
import com.moo3.server.service.PlanetService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Колонии игрока: занятия жителей и стройка — п. 4.1 и п. 10. */
@RestController
@RequestMapping("/api/games/{gameId}/planets/{planetId}")
public class ColonyController {

    private final PlanetService planetService;

    public ColonyController(PlanetService planetService) {
        this.planetService = planetService;
    }

    /** Перераспределение жителей колонии между фермерами, рабочими и учёными — п. 4.1. */
    @PostMapping("/population")
    public PlanetDto setPopulation(@PathVariable UUID gameId,
                                   @PathVariable UUID planetId,
                                   @Valid @RequestBody SetPopulationRequest request) {
        return planetService.setPopulation(gameId, planetId, request);
    }

    /**
     * Перевозка жителей в другую свою колонию — п. 4.1.1.
     * <p>
     * Грузовой флот резервируется из расчёта один грузовик на единицу населения; занятые
     * грузовики не возят еду, пока рейс не дойдёт. В ответ приходит колония-отправитель.
     */
    @PostMapping("/transfer")
    public PlanetDto transferPopulation(@PathVariable UUID gameId,
                                        @PathVariable UUID planetId,
                                        @Valid @RequestBody TransferPopulationRequest request) {
        return planetService.transferPopulation(gameId, planetId, request);
    }

    /** Смена стройки колонии — п. 10: здание, дома, товары или колониальная база. */
    @PostMapping("/project")
    public PlanetDto setProject(@PathVariable UUID gameId,
                                @PathVariable UUID planetId,
                                @Valid @RequestBody SetProjectRequest request) {
        return planetService.setProject(gameId, planetId, request);
    }

    /**
     * Выкуп стройки за кредиты — п. 10: казна платит за недостающие единицы производства,
     * и вещь достраивается тем же ходом. Дороже, пока сделано меньше половины.
     */
    @PostMapping("/buy")
    public PlanetDto buyProject(@PathVariable UUID gameId,
                                @PathVariable UUID planetId,
                                @AccessToken String accessToken) {
        return planetService.buyProject(gameId, planetId, accessToken);
    }

    /**
     * Продажа постройки — п. 10: здание исчезает с планеты, казна получает половину его
     * цены, содержание платить больше не за что. За ход колония продаёт одну постройку.
     */
    @PostMapping("/sell")
    public PlanetDto sellBuilding(@PathVariable UUID gameId,
                                  @PathVariable UUID planetId,
                                  @Valid @RequestBody SellBuildingRequest request) {
        return planetService.sellBuilding(gameId, planetId, request);
    }

    /**
     * Добавить проект в очередь стройки — п. 10.
     * <p>
     * Очередь короче {@code ColonyProject.QUEUE_LIMIT}, и в неё идёт то же, что в список
     * стройки. Если колония не строит ничего, проект встаёт сразу на стапель: очередь
     * забирается только после достроенного.
     */
    @PostMapping("/queue")
    public PlanetDto enqueue(@PathVariable UUID gameId,
                             @PathVariable UUID planetId,
                             @Valid @RequestBody SetProjectRequest request) {
        return planetService.enqueue(gameId, planetId, request);
    }

    /**
     * Убрать проект из очереди — п. 10. Вложенное производство при этом не пропадает:
     * единицы лежат на колонии, а не на проекте.
     */
    @DeleteMapping("/queue/{index}")
    public PlanetDto removeFromQueue(@PathVariable UUID gameId,
                                     @PathVariable UUID planetId,
                                     @PathVariable Integer index,
                                     @AccessToken String accessToken) {
        return planetService.removeFromQueue(gameId, planetId, index, accessToken);
    }

    /** Переставить проект в очереди — п. 10: новое место приходит в теле запроса. */
    @PostMapping("/queue/{index}/move")
    public PlanetDto moveInQueue(@PathVariable UUID gameId,
                                 @PathVariable UUID planetId,
                                 @PathVariable Integer index,
                                 @Valid @RequestBody MoveQueueRequest request) {
        return planetService.moveInQueue(gameId, planetId, index, request);
    }

    /**
     * Высадка десанта на чужую колонию — п. 12.
     * <p>
     * {@code planetId} — своя колония, откуда идёт десант; цель и его размер приходят
     * в теле запроса. Флотов в игре пока нет, поэтому цель обязана быть в той же системе.
     */
    @PostMapping("/invade")
    public InvasionResultDto invade(@PathVariable UUID gameId,
                                    @PathVariable UUID planetId,
                                    @Valid @RequestBody InvadeRequest request) {
        return planetService.invade(gameId, planetId, request);
    }

    /**
     * Заселение планеты готовой колониальной базой — п. 4.1.
     * <p>
     * {@code planetId} — колония, построившая базу; заселяемая планета приходит в теле
     * запроса. В ответ идёт вся система: планет в ней изменилось две.
     */
    @PostMapping("/colonize")
    public StarSystemDto colonize(@PathVariable UUID gameId,
                                  @PathVariable UUID planetId,
                                  @Valid @RequestBody ColonizeRequest request) {
        return planetService.colonize(gameId, planetId, request);
    }
}
