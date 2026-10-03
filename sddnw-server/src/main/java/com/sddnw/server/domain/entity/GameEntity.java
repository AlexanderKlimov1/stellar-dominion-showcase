package com.sddnw.server.domain.entity;

import com.sddnw.server.domain.enums.GalaxySize;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.VictoryKind;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/*
 * Индексы и уникальности объявлены ЗДЕСЬ, а не только в миграции.
 *
 * Объявленные в changelog'е, они существуют лишь там, где changelog прошёл. На H2, где
 * схему строит Hibernate по сущностям (режим балансового прогона), их не было вовсе — и
 * та же партия в пятьсот ходов шла 236 секунд вместо 159: каждая выборка внутри хода
 * перебирала таблицу целиком. Вторая причина проще: глядя на сущность, видно, по каким
 * полям её ищут, а лишний индекс заметен рядом с полем, а не в файле миграции
 * трёхмесячной давности.
 *
 * В Postgres они уже созданы, и Hibernate их не трогает: ddl-auto: validate сверяет
 * таблицы и колонки, но не индексы.
 */
@Entity
@Table(name = "game",
    indexes = {
        @Index(name = "ix_game_status_created", columnList = "status, created_at")
    })
public class GameEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "galaxy_size", nullable = false)
    private GalaxySize galaxySize;

    /** Ширина галактики в парсеках — п. 4.2: та же единица, что и дальность топлива. */
    @Column(name = "width_parsecs", nullable = false)
    private Integer widthParsecs;

    @Column(name = "height_parsecs", nullable = false)
    private Integer heightParsecs;

    @Column(name = "star_count", nullable = false)
    private Integer starCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private GameStatus status;

    @Column(name = "turn", nullable = false)
    private Integer turn;

    @Column(name = "total_players", nullable = false)
    private Integer totalPlayers;

    @Column(name = "max_human_players", nullable = false)
    private Integer maxHumanPlayers;

    @Column(name = "seed", nullable = false)
    private Long seed;

    /**
     * Идут ли в партии случайные галактические события — п. 11.1.
     * <p>
     * Признак партии, а не сервера: в MOO II события выключаются в окне новой игры, и
     * загруженное сохранение должно помнить, как играли.
     */
    @Column(name = "galactic_events", nullable = false)
    private Boolean galacticEvents = Boolean.TRUE;

    /**
     * Собирается ли в партии Высший совет — п. 3.
     * <p>
     * Признак партии, как и случайные события: замеры балансировки играют партию на
     * заданное число ходов, а избранный правитель обрывает её раньше срока. Игроку это
     * поле не показывается — в MOO II совет часть игры, а не настройка.
     */
    @Column(name = "council", nullable = false)
    private Boolean council = Boolean.TRUE;

    /**
     * Действуют ли в партии империи ИИ — трек техдолга, пункт 17.
     * <p>
     * Признак, а не настройка сервера, — как случайные события и совет. Выключают его
     * сценарии сквозного прогона, где два человека водят одинокие корабли: живой сосед
     * перехватывал курьера и отнимал родной мир, и проверки плавали. Игроку поле не
     * показывается: соседи в MOO II действуют всегда.
     */
    @Column(name = "ai_active", nullable = false)
    private Boolean aiActive = Boolean.TRUE;

    /**
     * Итог последнего совета галактики — п. 3: ход, избранный и числа заголовка сцены.
     * <p>
     * Сами голоса лежат строками (`council_vote`), а здесь то, что нужно, чтобы понять,
     * было ли голосование и чем кончилось, не читая их. Пусто — совет ещё не собирался.
     */
    @Column(name = "council_turn")
    private Integer councilTurn;

    @Column(name = "council_elected_player_id")
    private UUID councilElectedPlayerId;

    @Column(name = "council_total_votes")
    private Integer councilTotalVotes;

    @Column(name = "council_required_votes")
    private Integer councilRequiredVotes;

    /**
     * Приговор последнего совета отвергнут — п. 3: проигравший отказался подчиниться, и
     * партия пошла дальше. Признак нужен затем, чтобы отказ был ОДИН: без него кнопку
     * можно было бы жать снова и снова, каждый раз собирая новую войну.
     */
    @Column(name = "council_refused", nullable = false)
    private Boolean councilRefused = Boolean.FALSE;

    /**
     * Совет созван и ждёт голосов людей — п. 3. Голоса ИИ записаны на ходу созыва, а итог
     * подводится в конце СЛЕДУЮЩЕГО хода ({@code CouncilService.close}): ход считается на
     * сервере целиком, и спросить человека посреди фазы некого.
     */
    @Column(name = "council_open", nullable = false)
    private Boolean councilOpen = Boolean.FALSE;

    /**
     * Побеждает ли удержание Wardenhold — п. 3, {@code WardenholdRules}. Гостевая партия
     * включает его всегда; балансовые прогоны — никогда: партия обрывалась бы раньше
     * назначенного числа ходов, как и с советом.
     */
    @Column(name = "wardenhold_victory", nullable = false)
    private Boolean wardenholdVictory = Boolean.FALSE;

    /**
     * Кто сейчас один держит особую звезду; пусто — никто. Счёт непрерывный: стоит
     * появиться у звезды чужой колонии или пасть своей, и он начинается заново.
     */
    @Column(name = "wardenhold_holder_player_id")
    private UUID wardenholdHolderPlayerId;

    /** С какого хода нынешний держатель держит звезду без перерыва. */
    @Column(name = "wardenhold_since_turn")
    private Integer wardenholdSinceTurn;

    /**
     * Подводится ли итог по могуществу на ходу {@code MightVictoryRules.TURN_LIMIT} — п. 3.
     * Окно новой игры включает его по умолчанию; запрос без поля — нет: замеры и прогоны
     * играют партии заданной длины, и победа посреди них обрывала бы измерение.
     */
    @Column(name = "might_victory", nullable = false)
    private Boolean mightVictory = Boolean.FALSE;

    /**
     * Тесный старт — backlog-promo, пункт 5 (решение хозяина проекта): родные миры ставятся
     * близко друг к другу ({@code HomeworldAllocator}), и первое знакомство наступает
     * раньше. Только у гостевой партии: в прочих родные миры разнесены по галактике, как в
     * оригинале. Нужен лишь при старте, поэтому в слепок партии не идёт.
     */
    @Column(name = "close_start", nullable = false)
    private Boolean closeStart = Boolean.FALSE;

    /**
     * Богатый старт — backlog-promo, пункт 15 (решение хозяина проекта): империи начинают с
     * двигателем и химией и с кораблями у родной звезды ({@code GuestGameRules}). Только у
     * гостевой партии; нужен лишь при старте, поэтому в слепок партии не идёт.
     */
    @Column(name = "rich_start", nullable = false)
    private Boolean richStart = Boolean.FALSE;

    public Boolean getRichStart() {
        return richStart;
    }

    public void setRichStart(Boolean richStart) {
        this.richStart = richStart;
    }

    public Boolean getCloseStart() {
        return closeStart;
    }

    public void setCloseStart(Boolean closeStart) {
        this.closeStart = closeStart;
    }

    /**
     * Срок хода в секундах — backlog-promo, пункт 11 ({@code TurnClockRules}); пусто — без
     * срока. Истёк, а кто-то из людей ход не закончил, — ход считается сам.
     */
    @Column(name = "turn_seconds")
    private Integer turnSeconds;

    /** Когда начался текущий ход: от этого мгновения считается срок. */
    @Column(name = "turn_started_at")
    private OffsetDateTime turnStartedAt;

    public Integer getTurnSeconds() {
        return turnSeconds;
    }

    public void setTurnSeconds(Integer turnSeconds) {
        this.turnSeconds = turnSeconds;
    }

    public OffsetDateTime getTurnStartedAt() {
        return turnStartedAt;
    }

    public void setTurnStartedAt(OffsetDateTime turnStartedAt) {
        this.turnStartedAt = turnStartedAt;
    }

    public Boolean getMightVictory() {
        return mightVictory;
    }

    public void setMightVictory(Boolean mightVictory) {
        this.mightVictory = mightVictory;
    }

    public Boolean getWardenholdVictory() {
        return wardenholdVictory;
    }

    public void setWardenholdVictory(Boolean wardenholdVictory) {
        this.wardenholdVictory = wardenholdVictory;
    }

    public UUID getWardenholdHolderPlayerId() {
        return wardenholdHolderPlayerId;
    }

    public void setWardenholdHolderPlayerId(UUID wardenholdHolderPlayerId) {
        this.wardenholdHolderPlayerId = wardenholdHolderPlayerId;
    }

    public Integer getWardenholdSinceTurn() {
        return wardenholdSinceTurn;
    }

    public void setWardenholdSinceTurn(Integer wardenholdSinceTurn) {
        this.wardenholdSinceTurn = wardenholdSinceTurn;
    }

    public Boolean getCouncilOpen() {
        return councilOpen;
    }

    public void setCouncilOpen(Boolean councilOpen) {
        this.councilOpen = councilOpen;
    }

    @Column(name = "host_player_id")
    private UUID hostPlayerId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    /**
     * Победитель партии — п. 3. Пусто, пока партия идёт: заполняется вместе со статусом
     * {@code FINISHED} и больше не меняется.
     */
    @Column(name = "winner_player_id")
    private UUID winnerPlayerId;

    /** Чем победа взята — покорением или Высшим советом. */
    @Enumerated(EnumType.STRING)
    @Column(name = "victory_kind")
    private VictoryKind victoryKind;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /**
     * Версия строки для оптимистичной блокировки.
     * <p>
     * Игроки партии действуют одновременно, и без версии две правки одной строки просто
     * затирали друг друга: обе читали старое состояние и обе писали своё. Теперь второй
     * записи прилетает конфликт, и вызывающий её повторяет на свежих данных.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;


    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("slot ASC")
    private List<PlayerEntity> players = new ArrayList<>();

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<StarSystemEntity> starSystems = new ArrayList<>();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public GalaxySize getGalaxySize() {
        return galaxySize;
    }

    public void setGalaxySize(GalaxySize galaxySize) {
        this.galaxySize = galaxySize;
    }

    public Integer getWidthParsecs() {
        return widthParsecs;
    }

    public void setWidthParsecs(Integer widthParsecs) {
        this.widthParsecs = widthParsecs;
    }

    public Integer getHeightParsecs() {
        return heightParsecs;
    }

    public void setHeightParsecs(Integer heightParsecs) {
        this.heightParsecs = heightParsecs;
    }

    public Integer getStarCount() {
        return starCount;
    }

    public void setStarCount(Integer starCount) {
        this.starCount = starCount;
    }

    public GameStatus getStatus() {
        return status;
    }

    public void setStatus(GameStatus status) {
        this.status = status;
    }

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }

    public Integer getTotalPlayers() {
        return totalPlayers;
    }

    public void setTotalPlayers(Integer totalPlayers) {
        this.totalPlayers = totalPlayers;
    }

    public Integer getMaxHumanPlayers() {
        return maxHumanPlayers;
    }

    public void setMaxHumanPlayers(Integer maxHumanPlayers) {
        this.maxHumanPlayers = maxHumanPlayers;
    }

    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    public Boolean getGalacticEvents() {
        return galacticEvents;
    }

    public void setGalacticEvents(Boolean galacticEvents) {
        this.galacticEvents = galacticEvents;
    }

    public UUID getHostPlayerId() {
        return hostPlayerId;
    }

    public void setHostPlayerId(UUID hostPlayerId) {
        this.hostPlayerId = hostPlayerId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public UUID getWinnerPlayerId() {
        return winnerPlayerId;
    }

    public void setWinnerPlayerId(UUID winnerPlayerId) {
        this.winnerPlayerId = winnerPlayerId;
    }

    public VictoryKind getVictoryKind() {
        return victoryKind;
    }

    public void setVictoryKind(VictoryKind victoryKind) {
        this.victoryKind = victoryKind;
    }

    public Boolean getCouncil() {
        return council;
    }

    public Boolean getAiActive() {
        return aiActive;
    }

    public void setAiActive(Boolean aiActive) {
        this.aiActive = aiActive;
    }

    public void setCouncil(Boolean council) {
        this.council = council;
    }

    public Integer getCouncilTurn() {
        return councilTurn;
    }

    public void setCouncilTurn(Integer councilTurn) {
        this.councilTurn = councilTurn;
    }

    public UUID getCouncilElectedPlayerId() {
        return councilElectedPlayerId;
    }

    public void setCouncilElectedPlayerId(UUID councilElectedPlayerId) {
        this.councilElectedPlayerId = councilElectedPlayerId;
    }

    public Integer getCouncilTotalVotes() {
        return councilTotalVotes;
    }

    public void setCouncilTotalVotes(Integer councilTotalVotes) {
        this.councilTotalVotes = councilTotalVotes;
    }

    public Integer getCouncilRequiredVotes() {
        return councilRequiredVotes;
    }

    public void setCouncilRequiredVotes(Integer councilRequiredVotes) {
        this.councilRequiredVotes = councilRequiredVotes;
    }

    public Boolean getCouncilRefused() {
        return councilRefused;
    }

    public void setCouncilRefused(Boolean councilRefused) {
        this.councilRefused = councilRefused;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public List<PlayerEntity> getPlayers() {
        return players;
    }

    public void setPlayers(List<PlayerEntity> players) {
        this.players = players;
    }

    public List<StarSystemEntity> getStarSystems() {
        return starSystems;
    }

    public void setStarSystems(List<StarSystemEntity> starSystems) {
        this.starSystems = starSystems;
    }

    public void addPlayer(PlayerEntity player) {
        player.setGame(this);
        players.add(player);
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
