package com.moo3.server.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.BuildingEffects;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.LocalizedText;
import com.moo3.server.domain.RaceTrait;
import com.moo3.server.domain.RaceTraitGroup;
import com.moo3.server.domain.enums.BuildingEffectType;
import com.moo3.server.domain.enums.RaceEffectType;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Конструктор расы: особенности и их цена в очках — п. 7.
 * <p>
 * Устроен так же, как {@link BuildingCatalog} и {@link ResearchCatalog}: путь к файлу
 * задаёт {@code moo3.game.race-traits-file}, файл перечитывается, когда меняется время
 * его правки. Поэтому цены особенностей и сам их набор правятся без перезапуска сервера
 * и без миграций — в базе от расы игрока остаются только коды выбранного.
 * <p>
 * Цены особенностей правятся не только руками: их меняет экран «Стоимость особенностей
 * рас» из главного меню — см. {@link #updatePicks}. Правка ложится в тот же файл, и
 * следующий же запрос читает её через обычную перечитку по времени правки.
 * <p>
 * Особенность может менять сразу несколько вещей: правительство MOO II поднимает одно
 * и роняет другое. Часть эффектов действует на колонии — они те же, что у зданий, и
 * складываются с постройками; часть принадлежит своим подсистемам: наземному бою (п. 12),
 * шпионажу (п. 13) и кораблям (п. 8). Отдельно стоит климат родного мира: он не число,
 * а сам климат, поэтому у особенности своё поле.
 */
@Service
public class RaceTraitCatalog {

    private static final Logger log = LoggerFactory.getLogger(RaceTraitCatalog.class);

    private final Path file;

    /**
     * Связки, которые дорожают вместе, — свой файл РЯДОМ со справочником характеристик.
     * <p>
     * Путь выводится из пути к {@code race-traits.json}, а не спрашивается настройкой:
     * файлы всегда лежат рядом, и вторая настройка могла бы разъехаться с первой — а
     * разъехавшись, тихо отключила бы надбавки. {@code run.cmd} передаёт справочнику
     * абсолютный путь, и связки получают его автоматически.
     */
    private final Path pairsFile;

    private final ObjectMapper objectMapper;

    private volatile Snapshot snapshot;

    /**
     * Как часто проверяется, не изменился ли файл. Правки на лету остались, но обращение
     * к диску на каждый вызов ушло: справочник читают все запросы подряд, а редактируют
     * его руками и раз в час. Секунды задержки хватает, чтобы правка подхватилась «сразу».
     */
    private static final long RELOAD_CHECK_MS = 1_000L;

    /** Как называется файл связок рядом со справочником характеристик. */
    private static final String PAIRS_FILE = "pairs-balance.json";

    /** Когда файл проверяли в последний раз — по монотонным часам, а не по системным. */
    private volatile long checkedAt = 0L;

    public RaceTraitCatalog(GameProperties gameProperties, ObjectMapper objectMapper) {
        this.file = Path.of(gameProperties.raceTraitsFile()).toAbsolutePath();
        this.pairsFile = this.file.resolveSibling(PAIRS_FILE);
        this.objectMapper = objectMapper;
    }

    /** Группы особенностей в порядке описания. */
    public List<RaceTraitGroup> groups() {
        return current().groups();
    }

    /** Сколько очков выбора игрок может потратить. */
    public Integer picksBudget() {
        return current().picks();
    }

    /**
     * Сколько очков можно вернуть слабыми сторонами — потолок анти-выбора.
     * <p>
     * В MOO II такого числа не было: бюджет там десять, и десять же очков возвращали себе
     * Силикоиды — самая «продающая слабости» раса игры. То есть потолок в оригинале
     * существовал, просто совпадал с бюджетом. Здесь бюджет вырос до пятнадцати, а потолок
     * остался десятью: продавать слабости стало выгодно меньше, чем в оригинале, и это
     * намеренно.
     */
    public Integer antiPicksBudget() {
        return current().antiPicks();
    }

    public RaceTrait require(String code) {
        return require(current().byCode(), code);
    }

    private RaceTrait require(Map<String, RaceTrait> byCode, String code) {
        RaceTrait trait = byCode.get(code);
        if (trait == null) {
            throw new NotFoundException("race.traitNotFound", code);
        }
        return trait;
    }

    /**
     * Проверяет набор особенностей и возвращает его в порядке описания.
     * <p>
     * Правила те же, что в конструкторе MOO II: из группы берут не больше одного варианта,
     * а сумма цен не превышает бюджет очков. Отрицательные особенности бюджет пополняют,
     * поэтому «слабая» раса может позволить себе больше сильных сторон — но не сколько
     * угодно: вернуть можно не больше {@link #antiPicksBudget()} очков.
     */
    public List<String> validate(Collection<String> codes) {
        Snapshot current = current();
        return validate(codes, current.groupsByCode(), current.byCode(),
                current.picks(), current.antiPicks(), current.combinations());
    }

    /**
     * Связки с надбавкой — п. 7 (журнал, п. 3.71). Пусто — в справочнике их нет.
     * <p>
     * Отдаются наружу, потому что цену игрок должен видеть ДО покупки: конструктор на
     * клиенте считает очки на лету, и надбавка, всплывшая только в отказе сервера, читалась
     * бы как поломка.
     */
    public List<RaceCombination> combinations() {
        return current().combinations();
    }

    /**
     * Сколько очков набор доплачивает за связки — п. 7.
     * <p>
     * Связки СКЛАДЫВАЮТСЯ: набор, попавший в две сразу, платит за обе. Тройка при этом не
     * подразумевает своих пар — платится ровно то, что записано строкой справочника.
     */
    public Integer combinationExtra(Collection<String> codes) {
        return extraOf(codes, current().combinations());
    }

    /**
     * То же по ПЕРЕДАННОМУ списку связок, а не по загруженному справочнику.
     * <p>
     * Отдельный метод не для красоты: наборы готовых рас проверяются прямо при чтении файла,
     * когда загруженного справочника ещё нет, и спрашивать {@code current()} отсюда значит
     * уйти в разбор ПО КРУГУ. Первая же сборка так и легла — {@code StackOverflowError}
     * вместо отказа.
     */
    private Integer extraOf(Collection<String> codes, List<RaceCombination> combinations) {
        Set<String> chosen = Set.copyOf(codes);
        return combinations.stream()
                .filter(one -> chosen.containsAll(one.traits()))
                .mapToInt(RaceCombination::picks)
                .sum();
    }

    /**
     * Проверка набора ГОТОВОЙ расы — п. 5, п. 7.
     * <p>
     * <b>Бюджет и потолок ей не спрашиваются</b> — решение хозяина проекта (журнал, п. 3.70):
     * готовые расы не обязаны быть равными по силе, а цены сторон гонятся за измеренной
     * отдачей. Как только цена перестала быть договорной величиной и стала измеряемой,
     * требовать от портрета расы ровной суммы стало нечем: подорожала сторона — и Булрати
     * просто стоят двадцать два очка, то есть они сильнее расы, собранной игроком. Это и
     * есть MOO II, где Псилоны сильнее Гноламов.
     * <p>
     * Структурная правильность проверяется по-прежнему и целиком: коды существуют, из
     * группы-переключателя взята одна сторона, несовместимые вместе не стоят. Опечатка в
     * коде по-прежнему роняет чтение файла, а не тихо отнимает у расы её сторону.
     */
    private List<String> validateReadyRace(Collection<String> codes,
                                           Map<String, RaceTraitGroup> groupsByCode,
                                           Map<String, RaceTrait> byCode,
                                           List<RaceCombination> combinations) {
        return validate(codes, groupsByCode, byCode, null, null, combinations);
    }

    /**
     * @param budget     потолок трат; {@code null} — не спрашивать (готовая раса)
     * @param antiBudget потолок возврата; {@code null} — не спрашивать (готовая раса)
     */
    private List<String> validate(Collection<String> codes,
                                  Map<String, RaceTraitGroup> groupsByCode,
                                  Map<String, RaceTrait> byCode,
                                  Integer budget,
                                  Integer antiBudget,
                                  List<RaceCombination> combinations) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }

        Set<String> chosen = new LinkedHashSet<>();
        Set<String> singleGroups = new HashSet<>();
        int spent = 0;
        int returned = 0;
        for (String code : codes) {
            RaceTrait trait = require(byCode, code);
            if (!chosen.add(code)) {
                throw new ConflictException("race.traitTwice", code);
            }
            // Из группы-переключателя берут одну сторону; из группы особых способностей —
            // сколько угодно, там каждая стоит сама по себе.
            RaceTraitGroup group = groupsByCode.get(trait.groupCode());
            if (!Boolean.TRUE.equals(group.multiple()) && !singleGroups.add(trait.groupCode())) {
                throw new ConflictException("race.oneOfGroup", group.name());
            }
            spent += trait.picks();
            if (trait.picks() < 0) {
                returned -= trait.picks();
            }
        }

        // Несовместимости проверяются после набора: запрет двусторонний, и объявлен он
        // обычно у одной из сторон — литовор знает про фермеров, фермеры про литовора нет.
        for (String code : chosen) {
            RaceTrait trait = require(byCode, code);
            for (String forbidden : trait.excludes()) {
                if (chosen.contains(forbidden)) {
                    throw new ConflictException("race.incompatible", trait.name(), require(byCode, forbidden).name());
                }
            }
        }

        // НАДБАВКА ЗА СВЯЗКУ — п. 7 (журнал, п. 3.71): пара, которая вместе даёт больше
        // суммы своих половин, берёт с игрока сверх. Считается ПОСЛЕ набора и только когда
        // взяты все стороны связки.
        spent += extraOf(chosen, combinations);

        if (budget != null && spent > budget) {
            throw new ConflictException("race.overBudget", spent, budget);
        }
        // Потолок анти-выбора считается отдельно от бюджета: раса, продавшая слабостей
        // сверх него, укладывается в бюджет и всё равно незаконна.
        if (antiBudget != null && returned > antiBudget) {
            throw new ConflictException("race.overAntiBudget", returned, antiBudget);
        }
        return List.copyOf(codes);
    }

    /**
     * Во что вырастает этот строй — п. 14; {@code null} — расти некуда.
     * <p>
     * Развитые строи оригинала (Конфедерация, Империум, Федерация, Галактическое
     * единство) приходят с технологиями уровня «Advanced Government» и заменяют собой
     * прежний строй. Очками они не покупаются: в конструкторе их нет.
     */
    public GovernmentUpgrade governmentUpgrade(String governmentCode) {
        return current().upgradesByGovernment().get(governmentCode);
    }

    /** Все развитые строи — п. 14: по ним исследования решают, чью технологию показывать. */
    public java.util.Collection<GovernmentUpgrade> governmentUpgrades() {
        return current().upgradesByGovernment().values();
    }

    /**
     * Набор особенностей, каким он был куплен в конструкторе, — этап 1 балансировки.
     * <p>
     * Развитый строй {@code GovernmentService.upgradeAfterResearch} записывает игроку
     * вместо прежнего, и купленный строй из набора пропадает. Для игры это правильно:
     * действуют правила нового строя. Для замера — нет: очками платили за старый, а
     * развитый строй не стоит ничего, и бюджет расы «сам собой» падал на его цену
     * (единство за 6 очков вырастало в галактическое единство за 0 — раса на десять очков
     * становилась расой на четыре). Здесь развитый строй подменяется обратно тем, что он
     * заменил, — считать бюджет и сравнивать сборки можно только по купленному.
     */
    public List<String> asPicked(List<String> traitCodes) {
        Map<String, String> back = new LinkedHashMap<>();
        for (GovernmentUpgrade upgrade : governmentUpgrades()) {
            back.put(upgrade.code(), upgrade.replaces());
        }
        return traitCodes.stream().map(code -> back.getOrDefault(code, code)).toList();
    }

    /**
     * Набор особенностей готовой расы MOO II — п. 5: чем Псилон отличается от Клакона.
     * <p>
     * В MOO II готовая раса это тот же набор очков, что покупает игрок в конструкторе,
     * и каждый из тринадцати наборов стоит ровно бюджет — десять очков. Поэтому наборы
     * лежат в том же файле, что и особенности: коды не могут разъехаться со списком,
     * на который ссылаются, а правка расы не требует ни миграции, ни правки сервиса.
     *
     * @return коды особенностей расы; пусто — расы нет в файле, и она играет без сторон
     */
    public List<String> raceTraits(String raceCode) {
        return current().racesByCode().getOrDefault(raceCode, List.of());
    }

    /**
     * Что даёт игроку его раса — сумма эффектов выбранных особенностей.
     * <p>
     * Колониальная часть отдаётся тем же типом, что и здания: колония складывает её
     * с постройками и больше не различает, откуда взялась прибавка. Содержания у расы
     * нет — особенности ничего не стоят казне.
     */
    public RaceEffects effects(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return RaceEffects.NONE;
        }

        Map<RaceEffectType, Integer> amounts = new EnumMap<>(RaceEffectType.class);
        Map<BuildingEffectType, Integer> colony = new EnumMap<>(BuildingEffectType.class);

        Map<String, RaceTrait> byCode = current().byCode();
        for (String code : codes) {
            RaceTrait trait = byCode.get(code);
            // Неизвестный код — из слепка партии, снятого с другим набором особенностей:
            // такую особенность просто пропускаем, партия должна открыться.
            if (trait == null) {
                continue;
            }
            trait.effects().forEach((type, amount) -> {
                amounts.merge(type, amount, Integer::sum);
                if (type.getColonyEffect() != null) {
                    colony.merge(type.getColonyEffect(), amount, Integer::sum);
                }
            });
        }

        return new RaceEffects(
                new BuildingEffects(Map.copyOf(colony), 0),
                Map.copyOf(amounts));
    }

    /**
     * Название правительства расы — п. 14; {@code null}, если группы правительств
     * в наборе особенностей нет. Правительство это обычная особенность своей группы,
     * поэтому отдельного хранения ему не нужно.
     */
    public String government(Collection<String> codes) {
        RaceTrait government = governmentTrait(codes);
        return government == null ? null : government.name();
    }

    /** Код строя расы — п. 14; {@code null}, если строя в наборе нет. */
    public String governmentCode(Collection<String> codes) {
        RaceTrait government = governmentTrait(codes);
        return government == null ? null : government.code();
    }

    private RaceTrait governmentTrait(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        Map<String, RaceTrait> byCode = current().byCode();
        return codes.stream()
                .map(byCode::get)
                .filter(trait -> trait != null && GOVERNMENT_GROUP.equals(trait.groupCode()))
                .findFirst()
                .orElse(null);
    }

    /** Код группы правительств в файле конструктора — п. 14. */
    public static final String GOVERNMENT_GROUP = "government";

    /**
     * Границы цены особенности в очках расы.
     * <p>
     * Реконструкция: в MOO II цены лежат между −6 (самые тяжёлые недостатки) и +8
     * (правительства и мир Gaya). Границы взяты с запасом вдвое — балансу есть где
     * походить, а опечатка в лишний ноль дальше поля ввода не уйдёт.
     */
    private static final int MIN_PICKS = -20;
    private static final int MAX_PICKS = 20;

    /** Границы бюджета очков расы: в MOO II их десять. */
    private static final int MIN_BUDGET = 1;
    private static final int MAX_BUDGET = 100;

    /**
     * Меняет цены особенностей и бюджет очков прямо в файле справочника — п. 7.
     * <p>
     * Правится только цена: набор особенностей, их действия и описания остаются как были.
     * Поэтому файл не переписывается по разобранной модели, а правится деревом — всё,
     * чего редактор не касается (заметки, порядок полей, лишние поля будущих версий),
     * доезжает до диска в целости.
     * <p>
     * Идущим партиям правка не мешает: раса игрока хранится кодами особенностей, а не
     * ценой, и её эффекты от цены не зависят. Новая цена встретит следующего игрока,
     * который сядет собирать расу.
     * <p>
     * Запись атомарная — через соседний временный файл: сервер читает этот же файл на
     * каждый запрос, и застать его наполовину записанным нельзя.
     *
     * @param budget      новый бюджет очков; {@code null} — оставить прежний
     * @param picksByCode новые цены по кодам особенностей; пустая карта — только бюджет
     */
    public synchronized void updatePicks(Integer budget, Map<String, Integer> picksByCode) {
        ObjectNode root = readTree();

        if (budget != null) {
            if (budget < MIN_BUDGET || budget > MAX_BUDGET) {
                throw new ConflictException("race.budgetRange", MIN_BUDGET, MAX_BUDGET, budget);
            }
            root.put("picks", budget);
        }

        // Сперва находим все особенности, и только потом правим: половина применённой
        // правки хуже, чем отказ целиком — игрок не поймёт, что уехало, а что нет.
        Map<ObjectNode, Integer> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> change : picksByCode.entrySet()) {
            Integer picks = change.getValue();
            if (picks == null || picks < MIN_PICKS || picks > MAX_PICKS) {
                throw new ConflictException("race.picksRange", change.getKey(), MIN_PICKS, MAX_PICKS, picks);
            }
            changes.put(requireOption(root, change.getKey()), picks);
        }
        changes.forEach((option, picks) -> option.put("picks", picks));

        // Правка может сделать незаконной готовую расу — бюджетом ниже её цены или ценой,
        // уводящей её за потолок анти-выбора. Справочник проверяет все тринадцать наборов
        // при каждом чтении файла, поэтому такая правка роняет чтение ЦЕЛИКОМ: экран
        // выбора расы перестаёт работать весь, и починить его из того же экрана цен уже
        // нельзя — он тоже читает справочник. Поэтому записанное тут же перечитывается, и
        // отказ откатывает файл на прежний.
        String before = readFile();
        write(root);
        snapshot = null;
        checkedAt = 0L;
        try {
            current();
        } catch (RuntimeException broken) {
            writeRaw(before);
            snapshot = null;
            checkedAt = 0L;
            throw new ConflictException("race.editBreaksPresets", broken);
        }

        log.info("Цены особенностей расы изменены: {} особенностей, бюджет {}",
                picksByCode.size(), budget == null ? "прежний" : budget);
    }

    /** Особенность в дереве файла; неизвестный код — ошибка, а не молчаливый пропуск. */
    private ObjectNode requireOption(ObjectNode root, String code) {
        for (JsonNode group : root.path("groups")) {
            for (JsonNode option : group.path("options")) {
                if (code.equals(option.path("code").asText(null)) && option instanceof ObjectNode node) {
                    return node;
                }
            }
        }
        throw new NotFoundException("race.traitNotFound", code);
    }

    /**
     * Печать файла в том же виде, в каком его писали руками: два пробела отступа,
     * {@code "ключ": значение}, по элементу массива на строку и перевод строки 
.
     * <p>
     * Печать по умолчанию у Jackson другая — {@code "ключ" : значение}, массивы в одну
     * строку и перевод строки системный, — и правка одной цены переписывала бы весь файл
     * целиком, да ещё и меняла бы ему концы строк на Windows. Справочник читают и правят
     * руками, и такой «диф на весь файл» прятал бы настоящее изменение; 
 выбран потому,
     * что с ним лежат остальные справочники игры.
     */
    private DefaultPrettyPrinter printer() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(indenter)
                .withArrayIndenter(indenter);
        return printer.withSeparators(Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(Separators.Spacing.AFTER));
    }

    private ObjectNode readTree() {
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(file));
            if (!(root instanceof ObjectNode node)) {
                throw new IllegalStateException("Описание особенностей рас — не объект: " + file);
            }
            return node;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать особенности рас: " + file, e);
        }
    }

    /**
     * Пишет справочник обратно: сперва в соседний временный файл, потом переносом на
     * место. Перенос в пределах каталога атомарен, поэтому читатель видит либо старый
     * файл целиком, либо новый.
     */
    private void write(ObjectNode root) {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            String json = objectMapper.writer(printer()).writeValueAsString(root);
            Files.writeString(temporary, json + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Файловая система без атомарного переноса: обычная замена — лучшее,
                // что здесь можно сделать.
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось записать особенности рас: " + file, e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Временный файл остался — на следующей правке он будет перезаписан.
            }
        }
    }

    /** Файл как есть — им откатывается правка, сломавшая готовые расы. */
    private String readFile() {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать особенности рас: " + file, e);
        }
    }

    /** Возврат файла слово в слово — тем же атомарным переносом, что и обычная запись. */
    private void writeRaw(String json) {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось вернуть особенности рас: " + file, e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Временный файл остался — на следующей правке он будет перезаписан.
            }
        }
    }

    private Snapshot current() {
        long modifiedAt = modifiedAtCached();
        Snapshot loaded = snapshot;
        if (loaded == null || loaded.modifiedAt() != modifiedAt) {
            loaded = read(modifiedAt);
            snapshot = loaded;
        }
        return loaded;
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
            // Складываются времена ОБОИХ файлов: правка связок должна подхватываться так
            // же на лету, как правка цен. Сумма, а не максимум, — чтобы правка каждого из
            // них двигала число, даже если второй новее.
            long traits = Files.getLastModifiedTime(file).toMillis();
            return traits + (Files.exists(pairsFile)
                    ? Files.getLastModifiedTime(pairsFile).toMillis()
                    : 0L);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать особенности рас: " + file, e);
        }
    }

    /**
     * Связки из соседнего файла; его отсутствие — не беда, а обычное состояние партии без
     * подорожавших пар.
     */
    private List<CombinationEntry> readPairs() {
        if (!Files.exists(pairsFile)) {
            return List.of();
        }
        try {
            PairsFile parsed = objectMapper.readValue(Files.readAllBytes(pairsFile),
                    PairsFile.class);
            return parsed.combinations() == null ? List.of() : parsed.combinations();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать связки сторон: " + pairsFile, e);
        }
    }

    private Snapshot read(long modifiedAt) {
        TraitsFile parsed;
        try {
            parsed = objectMapper.readValue(Files.readAllBytes(file), TraitsFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать особенности рас: " + file, e);
        }
        if (parsed.groups() == null || parsed.groups().isEmpty()) {
            throw new IllegalStateException("В описании рас нет ни одной группы особенностей: " + file);
        }
        // Потолок анти-выбора спрашивается строго: без него слабости возвращали бы
        // сколько угодно очков, и это тихо разошлось бы с конструктором на клиенте.
        if (parsed.picks() == null || parsed.antiPicks() == null) {
            throw new IllegalStateException("В описании рас нет бюджета очков"
                    + " (\"picks\") или потолка анти-выбора (\"anti_picks\"): " + file);
        }

        List<RaceTraitGroup> groups = new ArrayList<>(parsed.groups().size());
        Map<String, RaceTraitGroup> groupsByCode = new LinkedHashMap<>();
        Map<String, RaceTrait> byCode = new LinkedHashMap<>();
        for (GroupEntry group : parsed.groups()) {
            List<RaceTrait> options = new ArrayList<>(group.options().size());
            for (OptionEntry option : group.options()) {
                RaceTrait trait = toTrait(group, option);
                options.add(trait);
                byCode.put(trait.code(), trait);
            }
            RaceTraitGroup parsedGroup = new RaceTraitGroup(group.code(), group.name(), group.description(),
                    Boolean.TRUE.equals(group.multiple()), List.copyOf(options));
            groups.add(parsedGroup);
            groupsByCode.put(parsedGroup.code(), parsedGroup);
        }

        // Связки с надбавкой — п. 7. Стороны проверяются так же, как в наборе расы: связка,
        // ссылающаяся на несуществующий код, роняет чтение файла, а не тихо не срабатывает.
        List<RaceCombination> combinations = new ArrayList<>();
        {
            for (CombinationEntry entry : readPairs()) {
                List<String> members = entry.traits() == null ? List.of() : entry.traits();
                if (members.size() < 2) {
                    throw new IllegalStateException(
                            "Связка — это две стороны или больше: " + members);
                }
                members.forEach(code -> require(byCode, code));
                combinations.add(new RaceCombination(List.copyOf(members),
                        entry.picks() == null ? 0 : entry.picks(), entry.note()));
            }
        }

        Map<String, List<String>> races = new LinkedHashMap<>();
        List<ReadyRace> readyRaces = new ArrayList<>();
        if (parsed.races() != null) {
            for (RaceEntry race : parsed.races()) {
                readyRaces.add(new ReadyRace(race.code(), race.name(), race.description(),
                        race.homeClimate(), race.color(),
                        race.traits() == null ? List.of() : List.copyOf(race.traits())));
                // Опечатка в коде особенности здесь означала бы расу, молча потерявшую
                // свою сторону, — поэтому набор готовой расы проверяется на состав так же
                // строго, как собранный игроком. А вот ЦЕНА ей не спрашивается: готовые
                // расы не обязаны быть равными по силе (журнал, п. 3.70).
                races.put(race.code(), validateReadyRace(race.traits(), groupsByCode, byCode,
                        combinations));
            }
        }

        // Развитые строи (п. 14) идут в тот же справочник особенностей, но не в группы:
        // купить их нельзя, их приносит технология и заменяет ими прежний строй.
        Map<String, GovernmentUpgrade> upgrades = new LinkedHashMap<>();
        if (parsed.governmentUpgrades() != null) {
            for (UpgradeEntry entry : parsed.governmentUpgrades()) {
                Map<RaceEffectType, Integer> effects = new EnumMap<>(RaceEffectType.class);
                if (entry.effects() != null) {
                    for (EffectEntry effect : entry.effects()) {
                        effects.merge(effect.type(), effect.amount(), Integer::sum);
                    }
                }
                byCode.put(entry.code(), new RaceTrait(entry.code(), entry.name(),
                        entry.description(), GOVERNMENT_GROUP, 0, List.of(), Map.copyOf(effects)));
                upgrades.put(entry.replaces(), new GovernmentUpgrade(
                        entry.code(), entry.name(), entry.replaces(), entry.requiredTech()));
            }
        }

        log.debug("Конструктор расы: {} групп, {} особенностей, {} готовых рас,"
                        + " {} очков выбора и {} анти-выбора из {}",
                groups.size(), byCode.size(), races.size(), parsed.picks(),
                parsed.antiPicks(), file);
        return new Snapshot(modifiedAt, List.copyOf(groups), Map.copyOf(groupsByCode),
                Map.copyOf(byCode), Map.copyOf(races), List.copyOf(readyRaces),
                parsed.picks(), parsed.antiPicks(),
                List.copyOf(combinations), Map.copyOf(upgrades));
    }

    private RaceTrait toTrait(GroupEntry group, OptionEntry option) {
        Map<RaceEffectType, Integer> effects = new EnumMap<>(RaceEffectType.class);
        if (option.effects() != null) {
            // Одинаковые типы у одной особенности складываются: описание не обязано их сводить.
            for (EffectEntry effect : option.effects()) {
                effects.merge(effect.type(), effect.amount(), Integer::sum);
            }
        }
        return new RaceTrait(
                option.code(),
                option.name(),
                option.description(),
                group.code(),
                option.picks(),
                option.excludes() == null ? List.of() : List.copyOf(option.excludes()),
                Map.copyOf(effects));
    }

    private record Snapshot(long modifiedAt,
                            List<RaceTraitGroup> groups,
                            Map<String, RaceTraitGroup> groupsByCode,
                            Map<String, RaceTrait> byCode,
                            Map<String, List<String>> racesByCode,
                            /** Готовые расы целиком, в порядке файла — п. 5. */
                            List<ReadyRace> readyRaces,
                            Integer picks,
                            /** Потолок анти-выбора — сколько очков можно вернуть слабостями. */
                            Integer antiPicks,
                            /** Связки с надбавкой — п. 7, журнал п. 3.71. */
                            List<RaceCombination> combinations,
                            /** Развитый строй по коду прежнего — п. 14. */
                            Map<String, GovernmentUpgrade> upgradesByGovernment) {
    }

    /**
     * Готовая раса MOO II — п. 5, п. 7.
     * <p>
     * Всё о расе лежит в ОДНОМ месте, в {@code race-traits.json}: и набор сторон, и имя с
     * описанием, и климат родного мира, и цвет на карте. Раньше это было разложено на три:
     * состав в файле, имя с цветом в таблице {@code race} (её наполнял Liquibase), а климат
     * ТРЕТЬЕЙ копией прямо в коде. Разъезжается такое молча, а с пустой схемой игра и вовсе
     * не поднимается — балансовому прогону в памяти справочнику взяться неоткуда.
     *
     * @param homeClimate климат родного мира: в MOO II он у всех земной, но правило живёт
     *                    данными, а не вшито в код
     */
    public record ReadyRace(String code, LocalizedText names, LocalizedText descriptions, String homeClimate,
                            String color, List<String> traits) {

        /** Название расы на языке читателя — п. 3.5. */
        public String name() {
            return names.text();
        }

        /** Описание расы на языке читателя — п. 3.5. */
        public String description() {
            return descriptions == null ? null : descriptions.text();
        }
    }

    /** Все готовые расы в порядке файла — он же порядок на экране выбора. */
    public List<ReadyRace> readyRaces() {
        return current().readyRaces();
    }

    /**
     * <b>Связка, которая стоит сверх своих частей</b> — п. 7 (журнал, п. 3.71).
     * <p>
     * Зачем понадобилась строка, которой в MOO II нет. Цены сторон СКЛАДЫВАЮТСЯ, а сила
     * иных пар УМНОЖАЕТСЯ: объединение даёт половину сверху с каждого работника, большой
     * мир — больше самих работников. Выразить произведение суммой нельзя ни при каком
     * числе — можно лишь подобрать цену, верную для одной величины империи и неверную для
     * всех прочих. Поэтому у пары появляется своя цена, и платится она только тогда, когда
     * взяты обе стороны.
     * <p>
     * Надбавка берётся из ЗАМЕРА: прибор считает, что связка даёт сверх суммы половин
     * ({@code extra}), и это число, делённое на курс очка, и есть её цена в очках.
     *
     * @param traits стороны связки; надбавка берётся, когда взяты все
     * @param picks  сколько очков связка стоит сверх своих частей
     * @param note   на чём измерена — тем же порядком, что и цены сторон
     */
    public record RaceCombination(List<String> traits, Integer picks, String note) {
    }

    /**
     * Во что вырастает строй империи — п. 14.
     *
     * @param code         код развитого строя: он заменит прежний в наборе особенностей
     * @param requiredTech технология, с которой он приходит
     */
    public record GovernmentUpgrade(String code, LocalizedText names, String replaces,
                                    String requiredTech) {

        /** Название строя на языке читателя — п. 3.5. */
        public String name() {
            return names.text();
        }
    }

    /** Связка в файле связок: какие стороны и сколько очков она берёт СВЕРХ своих частей. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CombinationEntry(List<String> traits, Integer picks, String note) {
    }

    /** Файл связок целиком — {@code pairs-balance.json} рядом со справочником. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PairsFile(List<CombinationEntry> combinations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TraitsFile(Integer picks,
                              @JsonProperty("anti_picks") Integer antiPicks,
                              List<GroupEntry> groups, List<RaceEntry> races,
                              @JsonProperty("government_upgrades")
                              List<UpgradeEntry> governmentUpgrades) {
    }

    /**
     * Развитый строй MOO II: приходит с технологией и заменяет прежний — п. 14.
     * <p>
     * Очками он не покупается и в конструкторе не показывается, поэтому и лежит вне
     * групп: таблица конструктора остаётся ровно таблицей оригинала.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UpgradeEntry(String code, LocalizedText name, LocalizedText description, String replaces,
                                @JsonProperty("required_tech") String requiredTech,
                                List<EffectEntry> effects) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GroupEntry(String code, LocalizedText name, LocalizedText description, Boolean multiple,
                              List<OptionEntry> options) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OptionEntry(String code, LocalizedText name, LocalizedText description, Integer picks,
                               List<String> excludes, List<EffectEntry> effects) {
    }

    /** Готовая раса MOO II: как она записана в файле — п. 5, п. 7. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RaceEntry(String code, LocalizedText name, LocalizedText description,
                             @JsonProperty("home_climate") String homeClimate,
                             String color, List<String> traits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EffectEntry(RaceEffectType type, Integer amount) {
    }
}
