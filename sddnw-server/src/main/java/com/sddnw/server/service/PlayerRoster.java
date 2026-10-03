package com.sddnw.server.service;

import com.sddnw.server.domain.RaceEffects;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.dto.StartGameRequest;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.AiObjective;
import com.sddnw.server.domain.enums.AiPersonality;
import com.sddnw.server.domain.enums.BannerColor;
import com.sddnw.server.domain.enums.PlanetClimate;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.NotFoundException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Состав участников партии — п. 3.2: люди, добор ИИ и раздача рас.
 * <p>
 * Расы раздаются без повторов, пока справочник не исчерпан. Каждый участник получает
 * свой пропуск: по нему сервер потом и опознаёт, кто именно ходит.
 */
@Service
public class PlayerRoster {

    private final RaceTraitCatalog raceTraitCatalog;

    public PlayerRoster(RaceTraitCatalog raceTraitCatalog) {
        this.raceTraitCatalog = raceTraitCatalog;
    }

    /** Новый участник-человек в свободном слоте. */
    public PlayerEntity human(String name, Integer slot, String requestedRaceCode, Set<String> takenRaces) {
        return human(name, slot, requestedRaceCode, takenRaces, null, Set.of());
    }

    /**
     * Новый участник-человек со знаменем — п. 3.2: выбранное игроком, если свободно; занятое
     * другим — отказ словами, а не тихая подмена: игрок выбирал цвет нарочно.
     *
     * @param takenColors цвета участников, уже сидящих в партии
     */
    public PlayerEntity human(String name, Integer slot, String requestedRaceCode, Set<String> takenRaces,
                              BannerColor banner, Set<String> takenColors) {
        PlayerEntity player = player(name, PlayerType.HUMAN, slot, resolveRace(requestedRaceCode, takenRaces));
        player.setColor(bannerColor(banner, player.getColor(), takenColors));
        return player;
    }

    /**
     * Цвет участника — п. 3.2, знамя MOO II ({@link BannerColor}).
     * <p>
     * Названное знамя берётся, если свободно, иначе отказ. Не названное — знамя своей расы
     * (у шести рас оригинала оно совпадает с их цветом), а занято и оно — первое свободное:
     * двух империй одного цвета в партии быть не должно, на карте их не различить. Знамён
     * восемь, империй в партии не больше восьми, поэтому свободное находится всегда; цвет
     * расы остаётся только запасом на случай, которого не бывает.
     */
    public String bannerColor(BannerColor requested, String raceColor, Set<String> takenColors) {
        Set<String> taken = takenColors.stream()
                .filter(color -> color != null)
                .map(color -> color.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (requested != null) {
            if (taken.contains(requested.hex())) {
                throw new ConflictException("banner.taken", requested);
            }
            return requested.hex();
        }
        return BannerColor.ofHex(raceColor)
                .filter(own -> !taken.contains(own.hex()))
                .or(() -> Arrays.stream(BannerColor.values())
                        .filter(banner -> !taken.contains(banner.hex()))
                        .findFirst())
                .map(BannerColor::hex)
                .orElse(raceColor);
    }

    /**
     * Раса, собранная игроком в конструкторе — п. 7.
     * <p>
     * Название и особенности необязательны: без них игрок остаётся с готовой расой
     * MOO II, которую выбрал, — со всеми её сторонами. Поэтому пустой набор здесь не
     * стирает расу, а означает «я собирать не стал»: иначе выбор Псилона отнимал бы у
     * него и науку, и изобретательность.
     * <p>
     * Своей расу делает название: игрок вводит его перед сборкой, и только оно отличает
     * «собрал сам» от «взял готовую». Набор проверяется здесь же — дальше в партию
     * попадает только то, что укладывается в очки расы.
     */
    public void applyRaceDesign(PlayerEntity player, String raceName, List<String> traitCodes) {
        boolean custom = raceName != null && !raceName.isBlank();
        if (!custom && (traitCodes == null || traitCodes.isEmpty())) {
            return;
        }

        List<String> traits = raceTraitCatalog.validate(traitCodes);
        if (custom) {
            player.setRaceName(raceName.trim());
        }
        player.setRaceTraitCodes(traits);
    }

    /** Свободные слоты добираются ИИ-игроками до общего количества игроков в галактике. */
    public List<PlayerEntity> aiPlayers(GameEntity game, List<PlayerEntity> humans) {
        return aiPlayers(game, humans, null);
    }

    /**
     * То же, но с заданными расами империй ИИ — этап 1 балансировки.
     * <p>
     * Прогон должен уметь сажать за соперников не случайные готовые расы, а именно те
     * сборки, ценность которых он меряет: без этого не измерить ни курс «очко → сила»,
     * ни силу отдельной стороны расы. Набор проверяется теми же правилами, что и сборка
     * игрока, — прогон не вправе играть тем, чего игроку собрать нельзя.
     *
     * @param designs расы империй ИИ по порядку слотов; {@code null} или короче — на
     *                оставшиеся слоты раздаются готовые расы, как обычно
     */
    public List<PlayerEntity> aiPlayers(GameEntity game, List<PlayerEntity> humans,
                                        List<StartGameRequest.AiEmpireDesign> designs) {
        Set<String> takenRaces = humans.stream()
                .map(PlayerEntity::getRaceCode)
                .collect(Collectors.toCollection(HashSet::new));
        // Знамёна людей уже выбраны (п. 3.2): ИИ разбирает оставшиеся, своё — если свободно.
        Set<String> takenColors = humans.stream()
                .map(PlayerEntity::getColor)
                .collect(Collectors.toCollection(HashSet::new));

        List<PlayerEntity> aiPlayers = new ArrayList<>();
        int slot = nextFreeSlot(humans);
        for (int i = humans.size(); i < game.getTotalPlayers(); i++) {
            RaceTraitCatalog.ReadyRace race = resolveRace(null, takenRaces);
            takenRaces.add(race.code());
            /*
              Имя империи ИИ — ОДНО название расы, без пометки «(ИИ)». Пометка жила в самом
              имени, а имя хранится в базе и подставляется в тексты всем читателям: в
              английском отчёте выходило «Your empire has been met by Saurath (ИИ)». Человека
              от соседа интерфейс отличает признаком `playerType`, и пометку ставит там, где
              она нужна (список лобби), — на языке того, кто смотрит. В MOO II соседи и
              зовутся просто по расе.
              Название берётся АНГЛИЙСКОЕ (`names().en()`) по той же причине: имя ложится в
              базу, и язык того, кто заводил партию, достался бы всем её читателям.
             */
            PlayerEntity ai = player(race.names().en(), PlayerType.AI, slot, race);
            ai.setColor(bannerColor(null, race.color(), takenColors));
            takenColors.add(ai.getColor());
            ai.setGame(game);
            giveCharacter(ai, game, slot);

            int design = i - humans.size();
            if (designs != null && design < designs.size()) {
                StartGameRequest.AiEmpireDesign wanted = designs.get(design);
                applyRaceDesign(ai, wanted.name(), wanted.traits());
                if (wanted.name() != null && !wanted.name().isBlank()) {
                    ai.setName(wanted.name());
                }
            }
            aiPlayers.add(ai);
            slot++;
        }
        return aiPlayers;
    }

    /**
     * Характер и устремление правителя ИИ — п. 15.
     * <p>
     * В MOO II сосед представлен двумя словами: «агрессивный промышленник». Первое решает,
     * чего от него ждать в дипломатии, второе — куда он вкладывается. Здесь они те же.
     * <p>
     * Достаются жребием от зерна партии и слота: партия с одним зерном должна собираться
     * одинаково, иначе загруженное сохранение расходилось бы с тем, что было. Раса тут ни
     * при чём — в оригинале правитель у расы свой на каждую партию, а не навсегда.
     * <p>
     * Тем же способом характер восстанавливается у слепков, снятых до его появления
     * ({@code GameSaveService}): старое сохранение должно открываться с живыми соседями,
     * а не с безликими.
     */
    public void giveCharacter(PlayerEntity ai, GameEntity game, Integer slot) {
        Random random = new Random(game.getSeed() * 131L + slot);
        ai.setAiPersonality(
                AiPersonality.values()[random.nextInt(AiPersonality.values().length)]);
        ai.setAiObjective(
                AiObjective.values()[random.nextInt(AiObjective.values().length)]);
    }

    public Integer nextFreeSlot(List<PlayerEntity> players) {
        return players.stream().mapToInt(PlayerEntity::getSlot).max().orElse(0) + 1;
    }

    /** Предпочтительный климат родной планеты по коду расы — п. 7. */
    public Map<String, PlanetClimate> homeClimatesByRace() {
        return races().stream()
                .collect(Collectors.toMap(
                        RaceTraitCatalog.ReadyRace::code,
                        race -> PlanetClimate.valueOf(race.homeClimate())));
    }

    /**
     * Что раса каждого игрока делает с его родным миром — п. 7.
     * <p>
     * Нужна раздаче родных систем: большой, богатый и бедный мир меняют планету в момент
     * её создания, а не по ходу партии. Спрашивается здесь, а не в {@link RaceService},
     * чтобы раздача не зависела от репозитория игроков: они у неё уже на руках.
     */
    public Map<UUID, RaceEffects> raceEffectsByPlayer(List<PlayerEntity> players) {
        return players.stream()
                .collect(Collectors.toMap(
                        PlayerEntity::getId,
                        player -> raceTraitCatalog.effects(player.getRaceTraitCodes())));
    }

    public Map<String, String> raceNames() {
        return races().stream()
                .collect(Collectors.toMap(RaceTraitCatalog.ReadyRace::code,
                        RaceTraitCatalog.ReadyRace::name, (first, second) -> first));
    }

    /**
     * Название родной звезды необязательно: клиент присылает и {@code null}, и пустую
     * строку, если игрок оставил поле пустым. Пустое имя — звезда останется с названием
     * из справочника, поэтому храним его как отсутствующее.
     */
    public String homeStarName(String requested) {
        return requested == null || requested.isBlank() ? null : requested.trim();
    }

    private PlayerEntity player(String name, PlayerType type, Integer slot, RaceTraitCatalog.ReadyRace race) {
        PlayerEntity player = new PlayerEntity();
        player.setName(name);
        player.setPlayerType(type);
        player.setSlot(slot);
        player.setRaceCode(race.code());
        // Готовая раса MOO II — это набор тех же очков, что покупает игрок в конструкторе
        // (п. 5): Псилон изобретателен и учён, Силикоид ест камень и никому не нравится.
        // Поэтому участник получает набор своей расы сразу, а не остаётся ни с чем.
        player.setRaceTraitCodes(raceTraitCatalog.raceTraits(race.code()));
        player.setColor(race.color());
        player.setAccessToken(UUID.randomUUID().toString().replace("-", ""));
        player.setJoinedAt(OffsetDateTime.now());
        return player;
    }

    /**
     * Выбор расы: запрошенная, если свободна, иначе первая свободная из справочника.
     * Когда справочник исчерпан, расы начинают повторяться — на восемь слотов их хватает.
     */
    private RaceTraitCatalog.ReadyRace resolveRace(String requestedCode, Set<String> takenCodes) {
        List<RaceTraitCatalog.ReadyRace> races = races();
        if (races.isEmpty()) {
            throw new ConflictException("race.catalogEmpty");
        }
        if (requestedCode != null) {
            return races.stream()
                    .filter(race -> race.code().equals(requestedCode))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("race.notFound", requestedCode));
        }
        return races.stream()
                .filter(race -> !takenCodes.contains(race.code()))
                .findFirst()
                .orElse(races.getFirst());
    }

    /**
     * Готовые расы — ИЗ ФАЙЛА, а не из базы (18.09.2026).
     * <p>
     * Лежали они в таблице {@code race}, наполняемой Liquibase, — второй копией того же
     * списка, что и в {@code race-traits.json}, где уже хранился их состав. Копия стоила
     * двух бед сразу: справочник в двух местах молча разъезжается, а на пустой схеме игра
     * не поднимается вовсе — балансовому прогону в памяти списку рас взяться неоткуда,
     * и первая же партия отвечала «Справочник рас пуст».
     */
    private List<RaceTraitCatalog.ReadyRace> races() {
        return raceTraitCatalog.readyRaces();
    }
}
