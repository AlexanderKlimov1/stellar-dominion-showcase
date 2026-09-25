package com.moo3.server.domain.entity;

import com.moo3.server.domain.enums.AiObjective;
import com.moo3.server.domain.enums.AiPersonality;
import com.moo3.server.domain.enums.PlayerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
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
@Table(name = "player",
    indexes = {
        @Index(name = "ix_player_game", columnList = "game_id"),
        @Index(name = "ix_player_game_token", columnList = "game_id, access_token")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_player_game_slot", columnNames = {"game_id", "slot"})
    })
public class PlayerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private GameEntity game;

    @Column(name = "slot", nullable = false)
    private Integer slot;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "player_type", nullable = false)
    private PlayerType playerType;

    /**
     * Характер правителя ИИ — п. 15; {@code null} у человека: за него решает человек.
     * В MOO II это первое из двух слов окна Report — «агрессивный промышленник».
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ai_personality")
    private AiPersonality aiPersonality;

    /** Устремление правителя ИИ — п. 15; второе слово того же окна. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ai_objective")
    private AiObjective aiObjective;

    /** Код расы из справочника race (заглушка, расширяется в п. 5 и п. 7). */
    @Column(name = "race_code", nullable = false)
    private String raceCode;

    /** Название расы, собранной игроком в конструкторе — п. 7; пусто — раса из справочника. */
    @Column(name = "race_name")
    private String raceName;

    /**
     * Особенности расы игрока — п. 7, кодами через запятую.
     * <p>
     * Набор выбирается один раз перед партией и дальше только читается вместе с игроком,
     * поэтому лежит колонкой, а не отдельной таблицей: искать по нему нечего. Сами
     * особенности с их ценой и действием живут в файле конструктора расы.
     */
    @Column(name = "race_traits", length = 512)
    private String raceTraits;

    @Column(name = "color", nullable = false)
    private String color;

    /** Секрет, по которому клиент подтверждает владение слотом. */
    @Column(name = "access_token", nullable = false)
    private String accessToken;

    @Column(name = "home_system_id")
    private UUID homeSystemId;

    /**
     * Название родной звезды, выбранное игроком при входе в игру.
     * Пусто — родная система получает имя из справочника звёзд наравне с остальными.
     */
    @Column(name = "home_star_name")
    private String homeStarName;

    @Column(name = "joined_at", nullable = false)
    private OffsetDateTime joinedAt;

    /**
     * До какого хода игрок дошёл — п. 11.1: номер хода, который он объявил законченным.
     * <p>
     * Игроки ходят одновременно, каждый в своём темпе, и галактика считается один раз,
     * когда закончили все. По этому полю и видно, кого ещё ждут. ИИ ждать не заставляет:
     * своих решений он пока не принимает.
     */
    @Column(name = "ended_turn", nullable = false)
    private Integer endedTurn = 0;

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


    /**
     * Текущее исследование — п. 9. Империя исследует одну технологию за раз, как в MOO II,
     * поэтому цель хранится тремя полями прямо на игроке, а не отдельной таблицей.
     * Все три пусты, пока игрок не выбрал, что исследовать.
     */
    @Column(name = "research_category_code")
    private String researchCategoryCode;

    @Column(name = "research_level_order")
    private Integer researchLevelOrder;

    @Column(name = "research_option_code")
    private String researchOptionCode;

    /** Очки исследований, вложенные в текущую цель. Прорыв обнуляет их: остаток пропадает. */
    @Column(name = "research_points", nullable = false)
    private Integer researchPoints = 0;

    /** Казна игрока — п. 10: доход колоний за ход минус содержание зданий. */
    @Column(name = "credits", nullable = false)
    private Integer credits = 0;

    /** Накопленные очки шпионажа — п. 13. */
    @Column(name = "espionage_points", nullable = false)
    private Integer espionagePoints = 0;

    /** Шпионы империи — п. 13: их строят колонии, каждый работает на разведку. */
    @Column(name = "spies", nullable = false)
    private Integer spies = 0;

    /**
     * Грузовые корабли империи — п. 4.1.1: они возят еду голодающим колониям.
     * <p>
     * Держатся числом, а не строками: грузовик не воюет, по карте не летает и ничем не
     * отличается от соседнего — важно только, сколько их всего.
     */
    @Column(name = "freighters", nullable = false)
    private Integer freighters = 0;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public GameEntity getGame() {
        return game;
    }

    public void setGame(GameEntity game) {
        this.game = game;
    }

    public Integer getSlot() {
        return slot;
    }

    public void setSlot(Integer slot) {
        this.slot = slot;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** Характер правителя ИИ — п. 15; {@code null} у человека. */
    public AiPersonality getAiPersonality() {
        return aiPersonality;
    }

    public void setAiPersonality(AiPersonality aiPersonality) {
        this.aiPersonality = aiPersonality;
    }

    /** Устремление правителя ИИ — п. 15; {@code null} у человека. */
    public AiObjective getAiObjective() {
        return aiObjective;
    }

    public void setAiObjective(AiObjective aiObjective) {
        this.aiObjective = aiObjective;
    }

    public PlayerType getPlayerType() {
        return playerType;
    }

    public void setPlayerType(PlayerType playerType) {
        this.playerType = playerType;
    }

    public String getRaceCode() {
        return raceCode;
    }

    public void setRaceCode(String raceCode) {
        this.raceCode = raceCode;
    }

    public Integer getSpies() {
        return spies;
    }

    public Integer getFreighters() {
        return freighters;
    }

    public void setFreighters(Integer freighters) {
        this.freighters = freighters;
    }

    public void setSpies(Integer spies) {
        this.spies = spies;
    }

    public Integer getEspionagePoints() {
        return espionagePoints;
    }

    public void setEspionagePoints(Integer espionagePoints) {
        this.espionagePoints = espionagePoints;
    }

    public String getRaceName() {
        return raceName;
    }

    public void setRaceName(String raceName) {
        this.raceName = raceName;
    }

    /** Коды особенностей расы; пустой список — раса без особенностей. */
    public List<String> getRaceTraitCodes() {
        return raceTraits == null || raceTraits.isBlank()
                ? List.of()
                : List.of(raceTraits.split(","));
    }

    public void setRaceTraitCodes(List<String> codes) {
        this.raceTraits = codes == null || codes.isEmpty() ? null : String.join(",", codes);
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public UUID getHomeSystemId() {
        return homeSystemId;
    }

    public void setHomeSystemId(UUID homeSystemId) {
        this.homeSystemId = homeSystemId;
    }

    public String getHomeStarName() {
        return homeStarName;
    }

    public void setHomeStarName(String homeStarName) {
        this.homeStarName = homeStarName;
    }

    public String getResearchCategoryCode() {
        return researchCategoryCode;
    }

    public void setResearchCategoryCode(String researchCategoryCode) {
        this.researchCategoryCode = researchCategoryCode;
    }

    public Integer getResearchLevelOrder() {
        return researchLevelOrder;
    }

    public void setResearchLevelOrder(Integer researchLevelOrder) {
        this.researchLevelOrder = researchLevelOrder;
    }

    public String getResearchOptionCode() {
        return researchOptionCode;
    }

    public void setResearchOptionCode(String researchOptionCode) {
        this.researchOptionCode = researchOptionCode;
    }

    public Integer getResearchPoints() {
        return researchPoints;
    }

    public void setResearchPoints(Integer researchPoints) {
        this.researchPoints = researchPoints;
    }

    public Integer getCredits() {
        return credits;
    }

    public void setCredits(Integer credits) {
        this.credits = credits;
    }

    public OffsetDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(OffsetDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Integer getEndedTurn() {
        return endedTurn;
    }

    public void setEndedTurn(Integer endedTurn) {
        this.endedTurn = endedTurn;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
