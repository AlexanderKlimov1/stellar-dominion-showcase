package com.moo3.server.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.LocalizedText;
import com.moo3.server.domain.ShipComponent;
import com.moo3.server.domain.WeaponModification;
import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.enums.ShipComponentSlot;
import com.moo3.server.domain.enums.ShipEffectType;
import com.moo3.server.domain.enums.WeaponKind;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Корпуса и компоненты кораблей из JSON-описания — п. 8.
 * <p>
 * Устроен так же, как {@link BuildingCatalog}: путь к файлу задаёт
 * {@code moo3.game.ships-file}, файл перечитывается, когда меняется время его правки. Из-за
 * этого новая пушка, броня или корпус — запись в файле, а не миграция: балансу кораблей
 * правки нужны чаще всего, а партии на сервере при этом не прерываются.
 * <p>
 * В базе от справочника остаются только коды: корпус и компоненты проекта
 * ({@code ship_design}, {@code ship_design_component}) — ссылки в этот файл.
 */
@Service
public class ShipCatalog {

    private static final Logger log = LoggerFactory.getLogger(ShipCatalog.class);

    /** Как часто проверяется, не изменился ли файл, — как у справочника зданий. */
    private static final long RELOAD_CHECK_MS = 1_000L;

    private final Path file;
    private final ObjectMapper objectMapper;

    /** Разобранный справочник и время правки файла, из которого он прочитан. */
    private volatile Snapshot snapshot;

    /** Когда файл проверяли в последний раз — по монотонным часам, а не по системным. */
    private volatile long checkedAt = 0L;

    public ShipCatalog(GameProperties gameProperties, ObjectMapper objectMapper) {
        this.file = Path.of(gameProperties.shipsFile()).toAbsolutePath();
        this.objectMapper = objectMapper;
    }

    /**
     * Все корпуса кораблей и платформ в порядке описания — от фрегата к Leviathan.
     * <p>
     * <b>Тел чудищ здесь нет</b> (п. 11.1): их прячет сам справочник, а не места показа.
     * Иначе телу дракона пришлось бы отказывать в каждом списке по отдельности — в окне
     * дизайна, в шести ячейках, в разделах технологий, — и однажды его бы там забыли:
     * платформы обороны так просачивались дважды. Кому чудище нужно, тот спрашивает его
     * по коду ({@link #hull}) или через {@link #monsterHull}.
     */
    public List<ShipHull> hulls() {
        return current().hulls().values().stream()
                .filter(hull -> !Boolean.TRUE.equals(hull.monster()))
                .toList();
    }

    /** Все компоненты в порядке описания, кроме частей чудищ — п. 11.1. */
    public List<ShipComponent> components() {
        return current().components().values().stream()
                .filter(component -> !Boolean.TRUE.equals(component.monster()))
                .toList();
    }

    /**
     * Тело чудища по коду — п. 11.1; годится только оно: попросив обычный корпус, зовущий
     * собрал бы чудищу фрегат и не заметил бы этого.
     */
    public ShipHull monsterHull(String code) {
        ShipHull hull = hull(code);
        if (!Boolean.TRUE.equals(hull.monster())) {
            throw new NotFoundException("ship.hullNotFound", code);
        }
        return hull;
    }

    /** Модификации оружия в порядке описания — п. 8. */
    public List<WeaponModification> modifications() {
        return List.copyOf(current().modifications().values());
    }

    /** Модификация по коду; неизвестная — отказ: проект с такой не соберёшь. */
    public WeaponModification modification(String code) {
        WeaponModification modification = current().modifications().get(code);
        if (modification == null) {
            throw new NotFoundException("ship.modificationNotFound", code);
        }
        return modification;
    }

    /** Пустое поле в описании значит «ничего не меняет», а не отсутствие числа. */
    private Integer percent(Integer value) {
        return value == null ? 0 : value;
    }

    /** Компоненты одного гнезда — для колонок экрана дизайна; части чудищ не в счёт. */
    public List<ShipComponent> components(ShipComponentSlot slot) {
        return current().components().values().stream()
                .filter(component -> !Boolean.TRUE.equals(component.monster()))
                .filter(component -> component.slot() == slot)
                .sorted(Comparator.comparing(ShipComponent::sortOrder))
                .toList();
    }

    public ShipHull hull(String code) {
        ShipHull hull = current().hulls().get(code);
        if (hull == null) {
            throw new NotFoundException("ship.hullNotFound", code);
        }
        return hull;
    }

    public ShipComponent component(String code) {
        ShipComponent component = current().components().get(code);
        if (component == null) {
            throw new NotFoundException("ship.componentNotFound", code);
        }
        return component;
    }

    /**
     * Самый дешёвый корпус справочника: с него начинается проект по умолчанию, который
     * империя получает в начале партии.
     */
    public ShipHull cheapestHull() {
        return current().hulls().values().stream()
                // Платформы обороны сюда не годятся: своей цены у них нет вовсе (их
                // покупают зданием), и «самым дешёвым корпусом» оказалась бы звёздная
                // база — на ней уехали бы колонисты.
                .filter(hull -> !Boolean.TRUE.equals(hull.platform()))
                // И тела чудищ тоже: цена у них ноль, и «самым дешёвым корпусом» стала бы
                // амёба — те же грабли, что со звёздной базой строкой выше (п. 11.1).
                .filter(hull -> !Boolean.TRUE.equals(hull.monster()))
                .min(Comparator.comparing(ShipHull::cost))
                .orElseThrow(() -> new IllegalStateException("В справочнике нет ни одного корпуса: " + file));
    }

    private Snapshot current() {
        long modifiedAt = modifiedAtCached();
        Snapshot value = snapshot;
        if (value == null || value.modifiedAt() != modifiedAt) {
            value = read(modifiedAt);
            snapshot = value;
        }
        return value;
    }

    /**
     * Время правки файла, но не чаще раза в секунду: между проверками возвращается
     * известное значение, и справочник не ходит на диск на каждом обращении.
     */
    private long modifiedAtCached() {
        long now = System.nanoTime() / 1_000_000L;
        Snapshot value = snapshot;
        if (value != null && now - checkedAt < RELOAD_CHECK_MS) {
            return value.modifiedAt();
        }
        checkedAt = now;
        return modifiedAt();
    }

    private long modifiedAt() {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать описание кораблей: " + file, e);
        }
    }

    private Snapshot read(long modifiedAt) {
        ShipsFile parsed;
        try {
            parsed = objectMapper.readValue(Files.readAllBytes(file), ShipsFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать описание кораблей: " + file, e);
        }

        if (parsed.hulls() == null || parsed.hulls().isEmpty()) {
            throw new IllegalStateException("В описании кораблей нет ни одного корпуса: " + file);
        }
        if (parsed.components() == null || parsed.components().isEmpty()) {
            throw new IllegalStateException("В описании кораблей нет ни одного компонента: " + file);
        }

        Map<String, ShipHull> hulls = new LinkedHashMap<>(parsed.hulls().size());
        parsed.hulls().stream()
                .sorted(Comparator.comparing(HullEntry::sortOrder))
                .forEach(entry -> hulls.put(entry.code(), toHull(entry)));

        Map<String, ShipComponent> components = new LinkedHashMap<>(parsed.components().size());
        parsed.components().stream()
                .sorted(Comparator.comparing(ComponentEntry::sortOrder))
                .forEach(entry -> components.put(entry.code(), toComponent(entry)));

        Map<String, WeaponModification> modifications = new LinkedHashMap<>();
        if (parsed.modifications() != null) {
            parsed.modifications().forEach(entry -> modifications.put(entry.code(),
                    new WeaponModification(
                            entry.code(),
                            entry.name(),
                            entry.description(),
                            // Пустой список значит «любому оружию»: описание не обязано
                            // перечислять виды, когда модификация подходит всем.
                            entry.appliesTo() == null || entry.appliesTo().isEmpty()
                                    ? EnumSet.allOf(WeaponKind.class)
                                    : EnumSet.copyOf(entry.appliesTo()),
                            percent(entry.damagePercent()),
                            percent(entry.attackPercent()),
                            percent(entry.shotsPercent()),
                            percent(entry.rangePercent()),
                            percent(entry.costPercent()),
                            percent(entry.spacePercent()),
                            Boolean.TRUE.equals(entry.piercesArmour()),
                            Boolean.TRUE.equals(entry.piercesShield()),
                            Boolean.TRUE.equals(entry.envelops()),
                            Boolean.TRUE.equals(entry.noRangePenalty()),
                            Boolean.TRUE.equals(entry.halvesEvasion()),
                            percent(entry.missileArmourPercent()),
                            percent(entry.missileSpeed()),
                            Boolean.TRUE.equals(entry.hitsEngine()),
                            entry.requiredTech())));
        }

        log.debug("Справочник кораблей: {} корпусов, {} компонентов и {} модификаций из {}",
                hulls.size(), components.size(), modifications.size(), file);
        return new Snapshot(modifiedAt, hulls, components, modifications);
    }

    private ShipHull toHull(HullEntry entry) {
        return new ShipHull(
                entry.code(),
                entry.name(),
                entry.description(),
                entry.space(),
                entry.cost(),
                entry.structure(),
                entry.command(),
                entry.hitChancePercent(),
                entry.systemFactor(),
                entry.requiredTech(),
                entry.sortOrder(),
                Boolean.TRUE.equals(entry.platform()),
                Boolean.TRUE.equals(entry.unarmed()),
                Boolean.TRUE.equals(entry.monster()));
    }

    private ShipComponent toComponent(ComponentEntry entry) {
        Map<ShipEffectType, Integer> effects = new EnumMap<>(ShipEffectType.class);
        if (entry.effects() != null) {
            // Одинаковые типы у одного компонента складываются: описание не обязано их сводить.
            for (Effect effect : entry.effects()) {
                effects.merge(effect.type(), effect.amount(), Integer::sum);
            }
        }
        return new ShipComponent(
                entry.code(),
                entry.name(),
                entry.description(),
                entry.slot(),
                entry.space(),
                entry.cost(),
                entry.requiredTech(),
                Map.copyOf(effects),
                entry.weaponKind(),
                Boolean.TRUE.equals(entry.envelops()),
                entry.modifications() == null ? null : List.copyOf(entry.modifications()),
                entry.sortOrder(),
                Boolean.TRUE.equals(entry.monster()));
    }

    private record Snapshot(long modifiedAt,
                            Map<String, ShipHull> hulls,
                            Map<String, ShipComponent> components,
                            Map<String, WeaponModification> modifications) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ShipsFile(String version, List<HullEntry> hulls, List<ComponentEntry> components,
                             @JsonProperty("weapon_modifications") List<ModificationEntry> modifications) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModificationEntry(String code,
                                     LocalizedText name,
                                     LocalizedText description,
                                     @JsonProperty("applies_to") List<WeaponKind> appliesTo,
                                     @JsonProperty("damage_percent") Integer damagePercent,
                                     @JsonProperty("attack_percent") Integer attackPercent,
                                     @JsonProperty("shots_percent") Integer shotsPercent,
                                     @JsonProperty("range_percent") Integer rangePercent,
                                     @JsonProperty("cost_percent") Integer costPercent,
                                     @JsonProperty("space_percent") Integer spacePercent,
                                     @JsonProperty("pierces_armour") Boolean piercesArmour,
                                     @JsonProperty("pierces_shield") Boolean piercesShield,
                                     Boolean envelops,
                                     @JsonProperty("no_range_penalty") Boolean noRangePenalty,
                                     @JsonProperty("halves_evasion") Boolean halvesEvasion,
                                     @JsonProperty("missile_armour_percent") Integer missileArmourPercent,
                                     @JsonProperty("missile_speed") Integer missileSpeed,
                                     @JsonProperty("hits_engine") Boolean hitsEngine,
                                     @JsonProperty("required_tech") String requiredTech) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HullEntry(String code,
                             LocalizedText name,
                             LocalizedText description,
                             Integer space,
                             Integer cost,
                             Integer structure,
                             Integer command,
                             @JsonProperty("hit_chance_percent") Integer hitChancePercent,
                             @JsonProperty("system_factor") Integer systemFactor,
                             @JsonProperty("required_tech") String requiredTech,
                             @JsonProperty("sort_order") Integer sortOrder,
                             Boolean platform,
                             Boolean unarmed,
                             Boolean monster) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ComponentEntry(String code,
                                  LocalizedText name,
                                  LocalizedText description,
                                  ShipComponentSlot slot,
                                  Integer space,
                                  Integer cost,
                                  @JsonProperty("required_tech") String requiredTech,
                                  @JsonProperty("weapon_kind") WeaponKind weaponKind,
                                  Boolean envelops,
                                  List<String> modifications,
                                  List<Effect> effects,
                                  @JsonProperty("sort_order") Integer sortOrder,
                                  Boolean monster) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Effect(ShipEffectType type, Integer amount) {
    }
}
