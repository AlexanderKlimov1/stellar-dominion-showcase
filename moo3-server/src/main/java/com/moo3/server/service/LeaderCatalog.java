package com.moo3.server.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.Leader;
import com.moo3.server.domain.LocalizedText;
import com.moo3.server.domain.enums.LeaderKind;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Лидеры из JSON-описания — п. 6.
 * <p>
 * Устроен так же, как {@link BuildingCatalog} и {@link ResearchCatalog}: путь задаёт
 * {@code moo3.game.leaders-file}, файл перечитывается, когда меняется время его правки, —
 * поэтому состав лидеров, их способности и сила правятся без перезапуска сервера и без
 * миграций. В базе от лидера остаётся только код — ссылка в этот файл.
 * <p>
 * Справочник держит и <b>способности</b>: у каждой свой род (общая, колониальная,
 * корабельная), единица измерения и то, во что она превращается в игре ({@code effect}).
 * Способность, которой в игре пока нет чему влиять, стоит с {@code effect: NONE} и
 * пояснением — она видна игроку, но ничего не делает. Так честнее, чем прятать её из
 * списка: в оригинале она у лидера есть.
 */
@Service
public class LeaderCatalog {

    private static final Logger log = LoggerFactory.getLogger(LeaderCatalog.class);

    /** Как часто проверяется, не изменился ли файл, — как у остальных справочников. */
    private static final long RELOAD_CHECK_MS = 1_000L;

    private final Path file;
    private final ObjectMapper objectMapper;

    private volatile Snapshot snapshot;
    private volatile long checkedAt = 0L;

    public LeaderCatalog(GameProperties gameProperties, ObjectMapper objectMapper) {
        this.file = Path.of(gameProperties.leadersFile()).toAbsolutePath();
        this.objectMapper = objectMapper;
    }

    /** Все лидеры справочника в порядке описания. */
    public List<Leader> all() {
        return List.copyOf(current().leaders().values());
    }

    /** Лидеры одного рода — колониальные или корабельные. */
    public List<Leader> of(LeaderKind kind) {
        return all().stream().filter(leader -> leader.kind() == kind).toList();
    }

    public Leader require(String code) {
        Leader leader = current().leaders().get(code);
        if (leader == null) {
            throw new NotFoundException("leader.notFound", code);
        }
        return leader;
    }

    /** Описания способностей — для экрана лидеров и для подсчёта прибавок. */
    public List<Ability> abilities() {
        return current().abilities();
    }

    /** Способность по коду; пусто — такой в справочнике нет. */
    public Ability ability(String code) {
        return current().abilities().stream()
                .filter(ability -> ability.code().equals(code))
                .findFirst()
                .orElse(null);
    }

    /** Звания по опыту: у колониальных и корабельных лидеров они свои. */
    public List<Rank> ranks(LeaderKind kind) {
        Ranks ranks = current().ranks();
        return kind == LeaderKind.COLONY ? ranks.colony() : ranks.ship();
    }

    /** Звание по накопленному опыту — последнее, чей порог пройден. */
    public Rank rank(LeaderKind kind, Integer experience) {
        Rank found = ranks(kind).get(0);
        for (Rank rank : ranks(kind)) {
            if (experience >= rank.experience()) {
                found = rank;
            }
        }
        return found;
    }

    /** Номер звания с нуля: по нему считаются сила способностей, цена и жалованье. */
    public Integer rankIndex(LeaderKind kind, Integer experience) {
        List<Rank> ranks = ranks(kind);
        int index = 0;
        for (int i = 0; i < ranks.size(); i++) {
            if (experience >= ranks.get(i).experience()) {
                index = i;
            }
        }
        return index;
    }

    private Snapshot current() {
        long modifiedAt = modifiedAtCached();
        Snapshot known = snapshot;
        if (known == null || known.modifiedAt() != modifiedAt) {
            known = read(modifiedAt);
            snapshot = known;
        }
        return known;
    }

    private long modifiedAtCached() {
        long now = System.nanoTime() / 1_000_000L;
        Snapshot known = snapshot;
        if (known != null && now - checkedAt < RELOAD_CHECK_MS) {
            return known.modifiedAt();
        }
        checkedAt = now;
        return modifiedAt();
    }

    private long modifiedAt() {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать описание лидеров: " + file, e);
        }
    }

    private Snapshot read(long modifiedAt) {
        LeadersFile parsed;
        try {
            parsed = objectMapper.readValue(Files.readAllBytes(file), LeadersFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать описание лидеров: " + file, e);
        }
        if (parsed.leaders() == null || parsed.leaders().isEmpty()) {
            throw new IllegalStateException("В описании лидеров нет ни одного лидера: " + file);
        }

        Map<String, Leader> leaders = new LinkedHashMap<>(parsed.leaders().size());
        for (Entry entry : parsed.leaders()) {
            leaders.put(entry.code(), new Leader(
                    entry.id(),
                    entry.code(),
                    entry.names(),
                    entry.title(),
                    entry.kind(),
                    entry.race(),
                    entry.startExperience(),
                    entry.skills() == null ? List.of() : entry.skills().stream()
                            .map(skill -> new Leader.LeaderSkill(
                                    skill.ability(), skill.value(), skill.perLevel()))
                            .toList(),
                    entry.techs() == null ? List.of() : entry.techs(),
                    Boolean.TRUE.equals(entry.techsRandomOne()),
                    entry.randomSkills() == null ? 0 : entry.randomSkills()));
        }

        log.debug("Справочник лидеров: {} лидеров, {} способностей из {}",
                leaders.size(), parsed.abilities().size(), file);
        return new Snapshot(modifiedAt, leaders, parsed.abilities(), parsed.ranks());
    }

    /** Способность лидера: род, единица и то, во что она превращается в игре. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ability(
            String code,
            String kind,
            @JsonProperty("name") LocalizedText names,
            String unit,
            @JsonProperty("description") LocalizedText descriptions,
            String works,
            String effect,
            @JsonProperty("note") LocalizedText notes
    ) {

        /** Название способности на языке читателя — п. 3.5. */
        public String name() {
            return names == null ? null : names.text();
        }

        /** Описание способности на языке читателя — п. 3.5. */
        public String description() {
            return descriptions == null ? null : descriptions.text();
        }

        /**
         * Пояснение «в игре пока не на что влиять» — на языке читателя, п. 3.5.
         * <p>
         * {@code unit}, {@code works} и {@code effect} рядом остаются кодами: их читает
         * код правил, а не игрок.
         */
        public String note() {
            return notes == null ? null : notes.text();
        }
    }

    /** Звание и опыт, с которого оно начинается. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rank(Integer experience, @JsonProperty("title") LocalizedText titles) {

        /** Звание на языке читателя — п. 3.5. */
        public String title() {
            return titles == null ? null : titles.text();
        }
    }

    private record Snapshot(long modifiedAt, Map<String, Leader> leaders,
                            List<Ability> abilities, Ranks ranks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LeadersFile(Ranks ranks, List<Ability> abilities, List<Entry> leaders) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Ranks(List<Rank> colony, List<Rank> ship) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Entry(
            Integer id,
            String code,
            @JsonProperty("name") LocalizedText names,
            LocalizedText title,
            LeaderKind kind,
            String race,
            Integer startExperience,
            List<SkillEntry> skills,
            List<String> techs,
            Boolean techsRandomOne,
            Integer randomSkills
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SkillEntry(String ability, Double value, Double perLevel) {
    }
}
