package com.moo3.server.domain.entity;

import com.moo3.server.domain.enums.GalaxySize;
import com.moo3.server.domain.enums.GameStatus;
import com.moo3.server.domain.enums.VictoryKind;
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
