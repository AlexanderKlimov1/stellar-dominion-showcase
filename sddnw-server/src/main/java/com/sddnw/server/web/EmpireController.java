package com.sddnw.server.web;

import com.sddnw.server.dto.AssignSpyRequest;
import com.sddnw.server.dto.EmpireInfoDto;
import com.sddnw.server.dto.EspionageDto;
import com.sddnw.server.dto.FleetDto;
import com.sddnw.server.dto.SpyDto;
import com.sddnw.server.dto.CouncilDto;
import com.sddnw.server.dto.CouncilVoteRequest;
import com.sddnw.server.service.EmpireInfoService;
import com.sddnw.server.service.EmpireService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Дела империи, не привязанные к планете: разведка (п. 13) и флот (п. 8). */
@RestController
@RequestMapping("/api/games/{gameId}")
@Validated
public class EmpireController {

    private final EmpireService empireService;
    private final EmpireInfoService empireInfoService;
    private final com.sddnw.server.service.CouncilService councilService;

    public EmpireController(EmpireService empireService, EmpireInfoService empireInfoService,
                            com.sddnw.server.service.CouncilService councilService) {
        this.empireService = empireService;
        this.empireInfoService = empireInfoService;
        this.councilService = councilService;
    }

    /** Разведка империи — п. 13: накопленные очки и приход за ход. */
    @GetMapping("/espionage")
    public EspionageDto espionage(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return empireService.espionage(gameId, accessToken);
    }

    /**
     * Задание шпиону — п. 13: отправить к сопернику воровать технологии или устраивать
     * саботаж либо отозвать домой, где агент пополняет запас очков разведки.
     */
    @PostMapping("/spies")
    public SpyDto assignSpy(@PathVariable UUID gameId, @Valid @RequestBody AssignSpyRequest request) {
        return empireService.assignSpy(gameId, request);
    }

    /**
     * Окно «Инфо» — п. 11.1: летопись империй для графика и описание знакомых рас.
     * <p>
     * Одним запросом на весь экран: он открывается целиком, и три отдельных запроса
     * рисовали бы его частями.
     */
    @GetMapping("/info")
    public EmpireInfoDto info(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return empireInfoService.info(gameId, accessToken);
    }

    /**
     * Выборы Высшего совета — п. 3: последнее голосование партии для сцены совета.
     * <p>
     * Пусто (204) — совет ещё не собирался: он сходится не раньше пятидесятого хода и
     * только при трёх живых империях.
     */
    @GetMapping("/council")
    public CouncilDto council(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return councilService.forPlayer(gameId, accessToken);
    }

    /**
     * Не подчиниться избранному правителю галактики — п. 3.
     * <p>
     * Отказ оживляет партию: победы советом больше нет, а всем, кто голосовал за
     * избранного, отказник объявлен врагом. Отказ в партии один — второй совет решает
     * окончательно.
     */
    @PostMapping("/council/refuse")
    public CouncilDto refuseCouncil(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return councilService.refuse(gameId, accessToken);
    }

    /**
     * Голос игрока в открытом совете — п. 3: за одного из кандидатов или воздержаться.
     * <p>
     * Итог подводится не здесь, а в конце хода: голоса ИИ решены на ходу созыва, и совет
     * сходится один раз для всех. До конца хода голос можно переменить.
     */
    @PostMapping("/council/vote")
    public CouncilDto voteCouncil(@PathVariable UUID gameId, @AccessToken String accessToken,
                                  @RequestBody CouncilVoteRequest request) {
        return councilService.vote(gameId, accessToken, request.choicePlayerId());
    }

    /** Флот империи — п. 8: проекты кораблей с учётом расы и сами корабли. */
    @GetMapping("/ships")
    public FleetDto ships(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return empireService.fleet(gameId, accessToken);
    }
}
