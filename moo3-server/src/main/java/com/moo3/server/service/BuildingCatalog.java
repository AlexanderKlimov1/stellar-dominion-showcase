package com.moo3.server.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.Building;
import com.moo3.server.domain.LocalizedText;
import com.moo3.server.domain.enums.BuildingEffectType;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Здания колоний из JSON-описания — п. 10.
 * <p>
 * Устроен так же, как {@link ResearchCatalog}: путь к файлу задаёт
 * {@code moo3.game.buildings-file}, файл перечитывается, когда меняется время его правки,
 * — поэтому стоимость, содержание и действие зданий правятся без перезапуска сервера и
 * без миграций.
 * <p>
 * В базе от зданий остаются только коды построенного ({@code planet_building}) — ссылки
 * в этот файл.
 */
@Service
public class BuildingCatalog {

    private static final Logger log = LoggerFactory.getLogger(BuildingCatalog.class);

    private final Path file;
    private final ObjectMapper objectMapper;

    /** Разобранный справочник и время правки файла, из которого он прочитан. */
    private volatile Snapshot snapshot;

    /**
     * Как часто проверяется, не изменился ли файл. Правки на лету остались, но обращение
     * к диску на каждый вызов ушло: справочник читают все запросы подряд, а редактируют
     * его руками и раз в час. Секунды задержки хватает, чтобы правка подхватилась «сразу».
     */
    private static final long RELOAD_CHECK_MS = 1_000L;

    /** Когда файл проверяли в последний раз — по монотонным часам, а не по системным. */
    private volatile long checkedAt = 0L;

    public BuildingCatalog(GameProperties gameProperties, ObjectMapper objectMapper) {
        this.file = Path.of(gameProperties.buildingsFile()).toAbsolutePath();
        this.objectMapper = objectMapper;
    }

    /** Все здания справочника в порядке описания. */
    public List<Building> all() {
        return List.copyOf(byCode().values());
    }

    /** Здания по коду — порядок описания сохраняется. */
    public Map<String, Building> byCode() {
        long modifiedAt = modifiedAtCached();
        Snapshot current = snapshot;
        if (current == null || current.modifiedAt() != modifiedAt) {
            current = new Snapshot(modifiedAt, read());
            snapshot = current;
        }
        return current.buildings();
    }

    public Building require(String code) {
        Building building = byCode().get(code);
        if (building == null) {
            throw new NotFoundException("colony.buildingNotFound", code);
        }
        return building;
    }

    /**
     * Время правки файла, но не чаще раза в секунду: между проверками возвращается
     * известное значение, и справочник не ходит на диск на каждом обращении.
     */
    private long modifiedAtCached() {
        long now = System.nanoTime() / 1_000_000L;
        Snapshot current = snapshot;
        if (current != null && now - checkedAt < RELOAD_CHECK_MS) {
            return current.modifiedAt();
        }
        checkedAt = now;
        return modifiedAt();
    }

    private long modifiedAt() {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать описание зданий: " + file, e);
        }
    }

    private Map<String, Building> read() {
        BuildingsFile parsed;
        try {
            parsed = objectMapper.readValue(Files.readAllBytes(file), BuildingsFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать описание зданий: " + file, e);
        }

        if (parsed.buildings() == null || parsed.buildings().isEmpty()) {
            throw new IllegalStateException("В описании зданий нет ни одного здания: " + file);
        }

        Map<String, Building> buildings = new LinkedHashMap<>(parsed.buildings().size());
        for (Entry entry : parsed.buildings()) {
            buildings.put(entry.code(), toBuilding(entry));
        }

        log.debug("Справочник зданий: {} зданий из {}", buildings.size(), file);
        return buildings;
    }

    private Building toBuilding(Entry entry) {
        Map<BuildingEffectType, Integer> effects = new EnumMap<>(BuildingEffectType.class);
        if (entry.effects() != null) {
            // Одинаковые типы у одного здания складываются: описание не обязано их сводить.
            for (Effect effect : entry.effects()) {
                effects.merge(effect.type(), effect.amount(), Integer::sum);
            }
        }
        return new Building(
                entry.code(),
                entry.name(),
                entry.description(),
                entry.cost(),
                entry.upkeep() == null ? 0 : entry.upkeep(),
                entry.requiredTech(),
                Map.copyOf(effects));
    }

    private record Snapshot(long modifiedAt, Map<String, Building> buildings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BuildingsFile(String version, List<Entry> buildings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Entry(String code,
                         LocalizedText name,
                         LocalizedText description,
                         Integer cost,
                         Integer upkeep,
                         @JsonProperty("required_tech") String requiredTech,
                         List<Effect> effects) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Effect(BuildingEffectType type, Integer amount) {
    }
}
