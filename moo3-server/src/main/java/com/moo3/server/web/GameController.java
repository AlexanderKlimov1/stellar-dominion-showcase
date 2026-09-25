package com.moo3.server.web;

import com.moo3.server.dto.AdvanceTurnsRequest;
import com.moo3.server.dto.AdvanceTurnsResponse;
import com.moo3.server.dto.BalanceTelemetryDto;
import com.moo3.server.dto.CreateGameRequest;
import com.moo3.server.dto.CreateGameResponse;
import com.moo3.server.dto.EndTurnRequest;
import com.moo3.server.dto.EndTurnResponse;
import com.moo3.server.dto.GalaxyMapDto;
import com.moo3.server.dto.GameDetailsDto;
import com.moo3.server.dto.GameSaveDto;
import com.moo3.server.dto.GameSummaryDto;
import com.moo3.server.dto.JoinGameRequest;
import com.moo3.server.dto.SaveGameRequest;
import com.moo3.server.dto.StartGameRequest;
import com.moo3.server.dto.TurnReportDto;
import com.moo3.server.service.AccountService;
import com.moo3.server.service.BalanceTelemetryService;
import com.moo3.server.service.GameService;
import com.moo3.server.service.TurnBatchService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Партия: лобби, старт, ходы, карта и сохранения — п. 3.1, п. 3.2, п. 11.1.
 * <p>
 * Действия внутри партии живут в своих контроллерах: колонии — {@link ColonyController},
 * исследования — {@link ResearchController}.
 */
@RestController
@RequestMapping("/api/games")
@Validated
public class GameController {

    private final GameService gameService;
    private final AccountService accountService;
    private final BalanceTelemetryService balanceTelemetryService;
    private final TurnBatchService turnBatchService;

    public GameController(GameService gameService, AccountService accountService,
                          BalanceTelemetryService balanceTelemetryService,
                          TurnBatchService turnBatchService) {
        this.gameService = gameService;
        this.accountService = accountService;
        this.balanceTelemetryService = balanceTelemetryService;
        this.turnBatchService = turnBatchService;
    }

    /** Список игр, видимый всем, кто видит IP сервера — п. 3.1. */
    @GetMapping
    public List<GameSummaryDto> listGames(@RequestParam(defaultValue = "false") Boolean openOnly) {
        return gameService.listGames(openOnly);
    }

    /**
     * Создание игры. Размер галактики необязателен, по умолчанию Huge.
     * <p>
     * До партии игрок называет себя — п. 3.1: пропуск учётной записи проверяется здесь,
     * на входе в партию. Дальше внутри партии ходит уже пропуск игрока: партия живёт
     * своей жизнью и об учётных записях ничего не знает.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateGameResponse createGame(@Valid @RequestBody CreateGameRequest request,
                                         @AccountToken String accountToken) {
        accountService.require(accountToken);
        return gameService.createGame(request);
    }

    @GetMapping("/{gameId}")
    public GameDetailsDto getGame(@PathVariable UUID gameId) {
        return gameService.getGame(gameId);
    }

    /** Присоединение к игре — п. 3.2; авторизация нужна так же, как и при создании (п. 3.1). */
    @PostMapping("/{gameId}/join")
    public CreateGameResponse joinGame(@PathVariable UUID gameId,
                                       @Valid @RequestBody JoinGameRequest request,
                                       @AccountToken String accountToken) {
        accountService.require(accountToken);
        return gameService.joinGame(gameId, request);
    }

    /** Старт игры: свободные слоты добираются ИИ-игроками до восьми — п. 3.2. */
    @PostMapping("/{gameId}/start")
    public GameDetailsDto startGame(@PathVariable UUID gameId,
                                    @Valid @RequestBody StartGameRequest request) {
        return gameService.startGame(gameId, request);
    }

    /**
     * Конец хода игрока — п. 11.1: игрок объявляет, что закончил, и ждёт остальных.
     * Галактика считается, когда закончили все люди партии; в ответе видно, посчитан ли
     * ход и кого ещё ждут.
     */
    @PostMapping("/{gameId}/turn/end")
    public EndTurnResponse endTurn(@PathVariable UUID gameId,
                                   @Valid @RequestBody EndTurnRequest request) {
        return gameService.endTurn(gameId, request);
    }

    /**
     * Прогнать подряд несколько ходов — этап 0 балансировки
     * (`balance-metrics-works.txt`): прогонам нужны сотни ходов, и дорога к серверу на
     * каждый ход стоит дороже самого хода. Считается всё тем же концом хода.
     */
    @PostMapping("/{gameId}/turn/advance")
    public AdvanceTurnsResponse advanceTurns(@PathVariable UUID gameId,
                                             @Valid @RequestBody AdvanceTurnsRequest request) {
        return turnBatchService.advance(gameId, request);
    }

    /**
     * Телеметрия партии для балансировки — этап 0: условия партии, империи с их расами и
     * стартовым положением и летопись каждой по ходам, одним слепком.
     */
    @GetMapping("/{gameId}/telemetry")
    public BalanceTelemetryDto telemetry(@PathVariable UUID gameId,
                                         @AccessToken String accessToken) {
        return balanceTelemetryService.of(gameId, accessToken);
    }

    /**
     * Что изменилось за последний посчитанный ход — п. 11.1.
     * <p>
     * Ход считается один раз на всех, поэтому у большинства игроков он случается не по их
     * запросу: они узнают о нём подпиской. Этот запрос нужен, когда подписки не было —
     * после перезагрузки страницы или обрыва связи.
     */
    @GetMapping("/{gameId}/turn/report")
    public TurnReportDto turnReport(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return gameService.turnReport(gameId, accessToken);
    }

    /** Сохранение партии — «Игра» → «Сохранить»: состояние на конец завершённого хода. */
    @PostMapping("/{gameId}/save")
    public GameSaveDto saveGame(@PathVariable UUID gameId,
                                @Valid @RequestBody SaveGameRequest request) {
        return gameService.saveGame(gameId, request);
    }

    /**
     * Карта галактики — п. 11.3. Звёзды видны все, названия и владельцы — только по
     * разведанным системам; {@code revealAll} — «Показать галактику» — раскрывает
     * подробности по всей галактике.
     */
    @GetMapping("/{gameId}/map")
    public GalaxyMapDto getMap(@PathVariable UUID gameId,
                               @AccessToken String accessToken,
                               @RequestParam(defaultValue = "false") Boolean revealAll) {
        return gameService.getMap(gameId, accessToken, revealAll);
    }

    @DeleteMapping("/{gameId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGame(@PathVariable UUID gameId, @AccessToken String accessToken) {
        gameService.deleteGame(gameId, accessToken);
    }

    /**
     * Убрать брошенную партию — распоряжением администратора, без пропуска хозяина.
     * <p>
     * Свой путь, а не тот же самый с другим пропуском: пропуск игрока обязателен по
     * устройству резолвера, и «удалить без него» на том же адресе выразить нечем. А по
     * смыслу это и есть другое действие — хозяйство сервера, как удаление сохранений.
     */
    @DeleteMapping("/{gameId}/admin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteByAdmin(@PathVariable UUID gameId, @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        gameService.deleteByAdmin(gameId);
    }
}
