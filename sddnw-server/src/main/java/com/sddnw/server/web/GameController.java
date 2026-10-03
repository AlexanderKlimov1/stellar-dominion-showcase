package com.sddnw.server.web;

import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.domain.enums.AccountRole;
import com.sddnw.server.dto.AdvanceTurnsRequest;
import com.sddnw.server.dto.AdvanceTurnsResponse;
import com.sddnw.server.dto.BalanceTelemetryDto;
import com.sddnw.server.dto.CreateGameRequest;
import com.sddnw.server.dto.CreateGameResponse;
import com.sddnw.server.dto.EndTurnRequest;
import com.sddnw.server.dto.EndTurnResponse;
import com.sddnw.server.dto.GalaxyMapDto;
import com.sddnw.server.dto.GameDetailsDto;
import com.sddnw.server.dto.GameSaveDto;
import com.sddnw.server.dto.GameSummaryDto;
import com.sddnw.server.dto.JoinGameRequest;
import com.sddnw.server.dto.SaveGameRequest;
import com.sddnw.server.dto.StartGameRequest;
import com.sddnw.server.dto.TurnReportDto;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.BalanceTelemetryService;
import com.sddnw.server.service.GameService;
import com.sddnw.server.service.GuestGameRules;
import com.sddnw.server.service.TurnBatchService;
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
        AccountEntity account = accountService.require(accountToken);
        // Гостевая партия — backlog-promo, пункт 1: настройки назначает сервер, а не окно
        // новой игры. Проверять их на клиенте мало — гость шлёт запрос сам.
        boolean guest = account.getRole() == AccountRole.GUEST;
        CreateGameRequest effective = guest ? GuestGameRules.restrict(request) : request;
        // Тесный старт — backlog-promo, пункт 5: только у гостевой партии.
        return gameService.createGame(effective, account.getId(), guest);
    }

    @GetMapping("/{gameId}")
    public GameDetailsDto getGame(@PathVariable UUID gameId) {
        return gameService.getGame(gameId);
    }

    /**
     * Партии, где у этой записи есть место, — «вернуться в партию» в главном меню (п. 3).
     * <p>
     * Стоит ДО {@code /{gameId}} нарочно: иначе «mine» разбиралось бы как опознаватель
     * партии и отвечало бы «не найдена».
     */
    @GetMapping("/mine")
    public List<GameSummaryDto> myGames(@AccountToken String accountToken) {
        return gameService.myGames(accountService.require(accountToken).getId());
    }

    /**
     * Возврат в свою партию — п. 3: запись получает пропуск СВОЕГО места.
     * <p>
     * Нужен после загрузки автосохранения (пропуска там выданы заново) и при входе с
     * другой машины, где хранилища браузера нет.
     */
    @PostMapping("/{gameId}/rejoin")
    public CreateGameResponse rejoin(@PathVariable UUID gameId,
                                     @AccountToken String accountToken) {
        return gameService.rejoin(gameId, accountService.require(accountToken).getId());
    }

    /**
     * Покинуть партию — backlog-promo, пункт 11: империю ведёт ИИ, партия больше не ждёт
     * ушедшего. Вернуться — {@code rejoin} или любое действие в партии.
     */
    @PostMapping("/{gameId}/leave")
    public GameDetailsDto leave(@PathVariable UUID gameId,
                                @Valid @RequestBody EndTurnRequest request) {
        return gameService.leave(gameId, request.accessToken());
    }

    /** Присоединение к игре — п. 3.2; авторизация нужна так же, как и при создании (п. 3.1). */
    @PostMapping("/{gameId}/join")
    public CreateGameResponse joinGame(@PathVariable UUID gameId,
                                       @Valid @RequestBody JoinGameRequest request,
                                       @AccountToken String accountToken) {
        // В чужую партию гость не входит (backlog-promo, пункт 1): там другие настройки и
        // живые люди, которые вправе знать, с кем играют.
        return gameService.joinGame(gameId, request, accountService.requireRegistered(accountToken).getId());
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
