package com.moo3.server.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.LocalizedText;
import com.moo3.server.dto.ResearchCategoryDto;
import com.moo3.server.dto.ResearchLevelDto;
import com.moo3.server.dto.ResearchOptionDto;
import com.moo3.server.dto.ResearchTreeDto;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Дерево технологий из JSON-описания — п. 9.
 * <p>
 * Путь к файлу задаёт {@code moo3.game.tech-file}. Дерево состоит из восьми разделов
 * без взаимных зависимостей; раздел — прямая последовательность уровней, на каждом
 * уровне игрок выбирает одну технологию из предложенных.
 * <p>
 * Файл перечитывается, когда меняется его время правки, поэтому дерево можно править
 * без перезапуска сервера — так же, как справочник названий звёзд.
 */
@Service
public class ResearchCatalog {

    private static final Logger log = LoggerFactory.getLogger(ResearchCatalog.class);

    private final Path file;
    private final ObjectMapper objectMapper;

    /** Разобранное дерево и время правки файла, из которого оно прочитано. */
    private volatile Snapshot snapshot;

    /**
     * Как часто проверяется, не изменился ли файл. Правки на лету остались, но обращение
     * к диску на каждый вызов ушло: справочник читают все запросы подряд, а редактируют
     * его руками и раз в час. Секунды задержки хватает, чтобы правка подхватилась «сразу».
     */
    private static final long RELOAD_CHECK_MS = 1_000L;

    /** Второй язык игры — п. 3.5. */
    private static final Locale RUSSIAN = Locale.of("ru");

    /** Когда файл проверяли в последний раз — по монотонным часам, а не по системным. */
    private volatile long checkedAt = 0L;

    public ResearchCatalog(GameProperties gameProperties, ObjectMapper objectMapper) {
        this.file = Path.of(gameProperties.techFile()).toAbsolutePath();
        this.objectMapper = objectMapper;
    }

    /**
     * Дерево на языке читателя — п. 3.5.
     * <p>
     * Дерево уходит на клиент готовым DTO и лежит в памяти разобранным, поэтому оба языка
     * собираются сразу: разбери его на языке первого запроса — и этот язык достался бы
     * всем следующим. Собрать оба стоит вдвое дешевле, чем одно чтение файла, а файл
     * читается раз в секунду.
     */
    public ResearchTreeDto tree() {
        return current().tree(LocaleContextHolder.getLocale());
    }

    /**
     * Дерево по-английски — для ПРАВИЛ, а не для показа.
     * <p>
     * Английское название технологии и есть её опознаватель: из него выводится код,
     * лежащий в базе у каждого изученного уровня. Всё, что сверяет названия, обязано
     * брать их отсюда, иначе русский запрос сверял бы русское с английским.
     */
    public ResearchTreeDto treeEnglish() {
        return current().english();
    }

    /**
     * Уровни, которые есть у каждой расы с первого хода, — п. 9: раздел и номер уровня.
     * <p>
     * В файле они записаны ссылкой «раздел:номер» ({@code power:1}), а не названием уровня.
     * Раньше стояло название, и правило сверяло «название уровня (название раздела)»
     * ТЕКСТОМ — на русском запросе (а партию заводит запрос) такая сверка не нашла бы ни
     * одного совпадения, и империи молча остались бы без стартовых технологий. Это, как уже
     * измерено, запертая игра: ни кораблестроения, ни знакомств, ни войн.
     */
    public List<StartingLevel> startingLevels() {
        return current().startingLevels();
    }

    /** Уровень, который выдаётся с первого хода: раздел и его номер — п. 9. */
    public record StartingLevel(String categoryCode, Integer levelOrder) {
    }

    /**
     * Разбор ссылки на уровень: {@code power:1} — первый уровень раздела Power.
     *
     * @throws IllegalStateException ссылка написана не так или такого уровня в дереве нет
     */
    private StartingLevel parseLevelRef(String reference, List<ResearchCategoryDto> categories) {
        String[] parts = reference.split(":");
        if (parts.length != 2) {
            throw new IllegalStateException(
                    "Стартовый уровень записан не как «раздел:номер»: " + reference + " (" + file + ")");
        }
        int order;
        try {
            order = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalStateException(
                    "Номер стартового уровня — не число: " + reference + " (" + file + ")", notANumber);
        }
        String categoryCode = parts[0].trim();
        boolean exists = categories.stream()
                .filter(category -> category.code().equals(categoryCode))
                .anyMatch(category -> category.levels().stream()
                        .anyMatch(level -> level.order() == order));
        if (!exists) {
            throw new IllegalStateException(
                    "Стартового уровня в дереве нет: " + reference + " (" + file + ")");
        }
        return new StartingLevel(categoryCode, order);
    }

    private Snapshot current() {
        long modifiedAt = modifiedAtCached();
        Snapshot found = snapshot;
        if (found == null || found.modifiedAt() != modifiedAt) {
            ResearchTreeDto english = read(Locale.ENGLISH);
            List<StartingLevel> starting = english.startingLevels().stream()
                    .map(reference -> parseLevelRef(reference, english.categories()))
                    .toList();
            found = new Snapshot(modifiedAt, english, read(RUSSIAN), starting);
            snapshot = found;
        }
        return found;
    }

    /** Раздел дерева по коду. */
    public ResearchCategoryDto category(String categoryCode) {
        return tree().categories().stream()
                .filter(category -> category.code().equals(categoryCode))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("research.categoryNotFound", categoryCode));
    }

    /** Уровень раздела по его порядковому номеру. */
    public ResearchLevelDto level(String categoryCode, Integer levelOrder) {
        return category(categoryCode).levels().stream()
                .filter(level -> level.order().equals(levelOrder))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("research.levelNotFound", levelOrder, categoryCode));
    }

    /** Технология уровня по коду. */
    public ResearchOptionDto option(String categoryCode, Integer levelOrder, String optionCode) {
        return level(categoryCode, levelOrder).options().stream()
                .filter(option -> option.code().equals(optionCode))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("research.optionNotFound", levelOrder, categoryCode, optionCode));
    }

    /**
     * Технология по одному её коду, без раздела и уровня — п. 15.
     * <p>
     * Дипломатии код технологии приходит от игрока сам по себе: в обмене и в подарке
     * называют технологию, а не место в дереве. Найденное место нужно и для записи
     * изученного ({@code player_technology} хранит раздел с уровнем), и для оценки —
     * ценность технологии это стоимость её уровня в очках исследований.
     *
     * @throws NotFoundException такого кода в дереве нет
     */
    public TechnologyPlace place(String technologyCode) {
        TechnologyPlace place = places().get(technologyCode);
        if (place == null) {
            throw new NotFoundException("research.techNotFound", technologyCode);
        }
        return place;
    }

    /**
     * Всё дерево разом, технология по коду — п. 15.
     * <p>
     * Заведено ради обмена технологиями у ИИ: он перебирает за один разговор десятки кодов
     * (что у соседа есть, а у меня нет, и наоборот), и поиск проходом по дереву на каждый
     * код превращался в тысячи проходов за ход — а ход и так считается внутри самого себя.
     * Правило построения то же, что у {@link #place}: карта и есть его единственный
     * источник, разъехаться им негде.
     */
    public Map<String, TechnologyPlace> places() {
        Map<String, TechnologyPlace> all = new LinkedHashMap<>();
        for (ResearchCategoryDto category : tree().categories()) {
            for (ResearchLevelDto level : category.levels()) {
                for (ResearchOptionDto option : level.options()) {
                    all.put(option.code(),
                            new TechnologyPlace(category.code(), level.order(), level.cost(), option));
                }
            }
        }
        return all;
    }

    /**
     * Место технологии в дереве — п. 15.
     *
     * @param levelCost стоимость уровня в очках исследований; ею и меряется ценность
     *                  технологии при обмене — дерево другой меры не даёт
     */
    public record TechnologyPlace(
            String categoryCode,
            Integer levelOrder,
            Integer levelCost,
            ResearchOptionDto option
    ) {
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
            throw new UncheckedIOException("Не удалось прочитать описание дерева технологий: " + file, e);
        }
    }

    private ResearchTreeDto read(Locale locale) {
        TechFile parsed;
        try {
            parsed = objectMapper.readValue(Files.readAllBytes(file), TechFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать описание дерева технологий: " + file, e);
        }

        if (parsed.categories() == null || parsed.categories().isEmpty()) {
            throw new IllegalStateException("В описании дерева технологий нет разделов: " + file);
        }
        validate(parsed);

        List<ResearchCategoryDto> categories = new ArrayList<>(parsed.categories().size());
        for (Map.Entry<String, Category> entry : parsed.categories().entrySet()) {
            categories.add(toCategory(entry.getKey(), entry.getValue(), locale));
        }

        List<String> startingLevels = parsed.startingLevels() == null || parsed.startingLevels().allRaces() == null
                ? List.of()
                : parsed.startingLevels().allRaces();

        log.debug("Дерево технологий: {} разделов, {} технологий из {}",
                categories.size(),
                categories.stream().flatMap(c -> c.levels().stream()).mapToInt(l -> l.options().size()).sum(),
                file);
        return new ResearchTreeDto(parsed.version(), categories, startingLevels);
    }

    /**
     * Проверяет дерево при чтении: коды на месте, не повторяются, а рекомендация называет
     * технологию ЭТОГО уровня.
     * <p>
     * Раньше проверять было нечего: код выводился из названия, а рекомендация сверялась с
     * названием же — опечатка в ней означала молча «рекомендации нет», и заметить это было
     * можно только замером («неизобретательная раса берёт рекомендованное реже» — а она
     * брала реже потому, что рекомендации не было вовсе). Теперь неверная ссылка — отказ
     * при чтении файла, и виден он сразу.
     */
    private void validate(TechFile parsed) {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Category> entry : parsed.categories().entrySet()) {
            List<Level> levels = entry.getValue().levels();
            for (int i = 0; i < levels.size(); i++) {
                Level level = levels.get(i);
                Set<String> ofLevel = new HashSet<>();
                for (Technology tech : level.technologies()) {
                    if (tech.code() == null || tech.code().isBlank()) {
                        throw new IllegalStateException("У технологии «" + tech.name().en()
                                + "» нет кода: " + file);
                    }
                    if (!seen.add(tech.code())) {
                        throw new IllegalStateException(
                                "Код технологии повторяется: " + tech.code() + " (" + file + ")");
                    }
                    ofLevel.add(tech.code());
                }
                if (level.recommended() == null) {
                    continue;
                }
                for (String recommended : level.recommended()) {
                    if (!ofLevel.contains(recommended)) {
                        throw new IllegalStateException("Рекомендация «" + recommended
                                + "» не называет технологию уровня " + entry.getKey() + ":" + (i + 1)
                                + " (" + file + ")");
                    }
                }
            }
        }
    }

    private ResearchCategoryDto toCategory(String code, Category category, Locale locale) {
        List<ResearchLevelDto> levels = new ArrayList<>(category.levels().size());
        for (int i = 0; i < category.levels().size(); i++) {
            Level level = category.levels().get(i);
            // Рекомендованные перечислены КОДАМИ: названия переводятся, а код — нет.
            Set<String> recommended = level.recommended() == null ? Set.of() : Set.copyOf(level.recommended());
            List<ResearchOptionDto> options = level.technologies().stream()
                    .map(tech -> new ResearchOptionDto(
                            tech.code(),
                            tech.name().text(locale),
                            tech.description() == null ? null : tech.description().text(locale),
                            recommended.contains(tech.code()),
                            // Раздел окна «Инфо» проставляет TechnologySections: он
                            // выводится из справочников зданий и кораблей, а дерево о них
                            // ничего не знает и знать не должно.
                            null,
                            null))
                    .toList();
            levels.add(new ResearchLevelDto(
                    i + 1,
                    level.levelName() == null ? null : level.levelName().text(locale),
                    level.cost(),
                    level.cumulativeCost(),
                    Boolean.TRUE.equals(level.general()),
                    options));
        }
        return new ResearchCategoryDto(code, category.name().text(locale),
                category.description() == null ? null : category.description().text(locale), levels);
    }

    private record Snapshot(long modifiedAt, ResearchTreeDto english, ResearchTreeDto russian,
                            List<StartingLevel> startingLevels) {

        /** Дерево на языке читателя; не русский — английский, он же запасной. */
        ResearchTreeDto tree(Locale locale) {
            return "ru".equals(locale.getLanguage()) ? russian : english;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TechFile(
            String version,
            @JsonProperty("tech_categories") LinkedHashMap<String, Category> categories,
            @JsonProperty("starting_levels") StartingLevels startingLevels) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Category(LocalizedText name, LocalizedText description, List<Level> levels) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Level(Integer cost,
                         @JsonProperty("cumulative_cost") Integer cumulativeCost,
                         @JsonProperty("level_name") LocalizedText levelName,
                         Boolean general,
                         List<Technology> technologies,
                         List<String> recommended,
                         /**
                          * Почему рекомендации нет: «Depends on strategy» и подобное из
                          * первоисточника. Замечание ЧИТАТЕЛЮ ФАЙЛА — игра его не
                          * спрашивает, и в дерево оно не уходит.
                          */
                         String comment) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Technology(String code, LocalizedText name, LocalizedText description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StartingLevels(@JsonProperty("all_races") List<String> allRaces) {
    }
}
