package com.moo3.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.domain.entity.history.BalanceRunEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Оракул сборок — этап 3 плана (`balance-metrics-works.txt`), «взломщик».
 * <p>
 * Этап 2 назначает цены по СЛУЧАЙНЫМ сборкам, а игрок случайной не играет — он ищет
 * сильнейшую. Значит, честные в среднем цены могут оставить в таблице комбинацию, которая
 * бьёт всё остальное, и заметить это случайными сборками нельзя: сильнейшая встречается
 * среди них раз на тысячу. Оракул ищет её нарочно, и найденное идёт в следующий замер.
 *
 * <p><b>Поколениями с отсевом.</b> Сборки играют партии, худшая половина отсеивается, на её
 * место приходят потомки выживших — та же сборка с одной заменённой стороной. Партии от
 * поколения к поколению удлиняются: короткая дёшево отсеивает заведомо слабых, а спорить за
 * первое место нужно на длинной. Перебирать все сборки нельзя — их миллионы, а партия стоит
 * минуту.
 *
 * <p><b>Соседи тасуются каждое поколение.</b> Сила меряется ДОЛЕЙ, то есть всегда
 * относительно тех, кто сидит в той же партии. Закрепи соседей — и оракул начнёт искать не
 * сильнейшую сборку, а удачное соседство.
 */
@Service
public class BalanceOracleService {

    private static final Logger log = LoggerFactory.getLogger(BalanceOracleService.class);

    /**
     * Столько партий идёт разом.
     * <p>
     * Прежние четыре брались «как у замера, ходы упираются в базу» — и это оказалось
     * неправдой: посреди прогона на двенадцатиядерной машине занято было ядро с четвертью
     * (сервер 0,78, Postgres 0,21). Упирался прогон не в базу, а в собственные барьеры —
     * см. {@link #playFloor}.
     */
    private static final int GAME_WORKERS = 10;

    /**
     * Длиннее этого партии не растут — п. 3 плана (решение хозяина проекта 19.09.2026).
     * <p>
     * <b>Было 300, и это оказалось потолком не времени, а зрения.</b> Оракул растит длину
     * партии вдвое за поколение от базы, заданной заказом, — с базой в пятьдесят ходов
     * отсев решался на партиях в 50, 100 и 200 ходов, и до потолка доживали те, кто силён
     * СПРИНТЕРОМ. Сторона, которая окупается поздно, вылетала раньше, чем успевала себя
     * показать: в круге 5 лидером вышла сборка, продавшая учёных, бойцов, пилотов и
     * канониров, — а замер на пятистах ходах тем же вечером назвал «плохих учёных» дорогими
     * (−3,43 при цене −1). Спорили не приборы, а горизонты.
     * <p>
     * Теперь потолок равен длине партии замера: приборы обязаны смотреть на одну и ту же
     * игру, иначе их приговоры несравнимы по построению.
     */
    private static final int MAX_TURNS = 500;

    /**
     * Сколько поколений идёт РАЗВЕДКА — та часть поиска, где освободившиеся места занимают
     * потомки выживших.
     * <p>
     * Дальше поиск кончается и начинается спор за первое место: поле сжимается вдвое каждое
     * поколение и больше не пополняется, пока не останется столько сборок, сколько мест в
     * партии. Весь остаток партий достаётся этому финальному столу — за тем и затевалось:
     * приговор «есть доминирующая сборка» выносится по разнице, а разница видна, только
     * когда ошибка меньше неё.
     */
    private static final int EXPLORE_GENERATIONS = 4;

    /**
     * Во сколько столов упирается отсев — то есть сколько сборок доживает до финала.
     * <p>
     * Было в один стол: восемь финалистов на пятьдесят семь сторон таблицы. Приговор
     * «доминирующая сборка» такое поле выносит уверенно, а вот второе условие плана — «нет
     * мёртвой стороны» — нет: сторона, не попавшая в восемь сборок, просто не попала в
     * восемь сборок, и назвать её мёртвой значит соврать. Два стола дают шестнадцать
     * финалистов при тех же партиях — каждому достанется вдвое меньше, но ошибка растёт
     * лишь в полтора раза (как корень), а покрытие сторон удваивается.
     */
    private static final int FINAL_TABLES = 2;

    /** Сколько партий считать разом: настройка по времени суток — п. 2 этапа 2. */
    private final BalanceLoad load;

    private final BalanceGameRunner games;
    private final BalanceRunProgress progress;
    private final BalanceOracleRules rules;
    private final BalanceVerdictRules verdictRules;
    private final RaceBuildGenerator builds;
    private final RaceTraitCatalog raceTraits;
    private final ObjectMapper objectMapper;

    public BalanceOracleService(BalanceGameRunner games,
                                BalanceRunProgress progress,
                                BalanceOracleRules rules,
                                BalanceVerdictRules verdictRules,
                                RaceBuildGenerator builds,
                                RaceTraitCatalog raceTraits,
                                ObjectMapper objectMapper,
                              BalanceLoad load) {
        this.load = load;
        this.games = games;
        this.progress = progress;
        this.rules = rules;
        this.verdictRules = verdictRules;
        this.builds = builds;
        this.raceTraits = raceTraits;
        this.objectMapper = objectMapper;
    }

    /**
     * Ищет сильнейшие сборки и возвращает ладдер с ответом на оба условия плана: есть ли
     * доминирующая сборка и какие стороны в верхушку не вошли.
     *
     * @param run        запись прогона: галактика, число империй, число партий и ходов
     * @param population сколько сборок в первом поколении
     * @param played     счётчик сыгранных партий — по нему пульт показывает ход поиска
     */
    public BalanceOracleRules.Search search(BalanceRunEntity run, int population,
                                            AtomicInteger played) {
        int seats = run.getEmpires();
        int budget = raceTraits.picksBudget();
        int anti = raceTraits.antiPicksBudget();
        Random random = new Random(run.getSeed());

        List<List<String>> alive = new ArrayList<>();
        while (alive.size() < population) {
            List<String> build = builds.build(budget, anti, random);
            if (!build.isEmpty()) {
                alive.add(build);
            }
        }

        // Итоги каждой сборки копятся по всем поколениям, где она участвовала: выжившая
        // сборка тем и отличается от новичка, что о ней известно больше.
        Map<String, List<Double>> results = new LinkedHashMap<>();
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        int generation = 0;
        // Счётчик партий зовётся не games: так называется играющая часть, и локальная
        // переменная закрыла бы её собой.
        int rounds = 0;

        ExecutorService pool = Executors.newFixedThreadPool(GAME_WORKERS, runnable -> {
            Thread thread = new Thread(runnable, "balance-oracle");
            thread.setDaemon(true);
            return thread;
        });
        try {
            while (alive.size() >= 2 && rounds < run.getGames()) {
                // Поле перестало меняться — весь остаток играется одной пачкой, без
                // барьеров между «поколениями», которых больше нет (см. playFloor).
                if (alive.size() <= seats * FINAL_TABLES) {
                    generation = playFloor(run, alive, seats, generation, rounds,
                            random, results, byKey, played, pool);
                    break;
                }

                int turns = rules.turns(run.getTurns(), generation, MAX_TURNS);
                List<List<Integer>> tables = rules.deal(alive.size(), seats, random);

                List<Future<?>> playing = new ArrayList<>();
                List<List<String>> current = List.copyOf(alive);
                for (List<Integer> table : tables) {
                    if (rounds >= run.getGames()) {
                        break;
                    }
                    rounds++;
                    playing.add(submit(run, turns, table, current, run.getSeed() * 31L + rounds,
                            results, byKey, played, pool));
                }
                for (Future<?> one : playing) {
                    join(one);
                }

                List<List<String>> ranked = new ArrayList<>(alive);
                ranked.sort((a, b) -> Double.compare(mean(results, b), mean(results, a)));
                if (alive.size() > seats * FINAL_TABLES) {
                    // Поле сжимается ВСЕРЬЁЗ: половина слабейших уходит совсем. Пополнять
                    // его потомками до прежнего размера, как было сперва, — значит не
                    // сжимать вовсе: поле крутится на месте, и ни одна сборка не набирает
                    // партий, чтобы её сила стала точной. На первом же прогоне так и вышло:
                    // тридцать «поколений» по четыре партии и ошибки в четыре-шесть
                    // пунктов при разнице в пять.
                    int keep = rules.survivors(ranked.size());
                    List<List<String>> next = new ArrayList<>(ranked.subList(0, keep));
                    if (generation < EXPLORE_GENERATIONS) {
                        // Пока идёт разведка, освободившиеся места занимают потомки: это и
                        // есть поиск. Ближе к финалу поиск кончается и начинается спор за
                        // первое место, а спорить должны те, кого уже отобрали.
                        int children = 0;
                        while (next.size() < ranked.size() && children < ranked.size() * 4) {
                            children++;
                            List<String> child = rules.mutate(
                                    next.get(random.nextInt(keep)), builds, budget, anti, random);
                            if (!child.isEmpty()) {
                                next.add(child);
                            }
                        }
                    }
                    alive = next;
                }
                // Когда сборок осталось на одну партию, состав больше не меняется: весь
                // остаток партий уходит финалистам, и только так их сила становится точной.
                generation++;
                log.info("Оракул сборок {}: поколение {}, сборок {}, партий {} по {} ходов",
                        run.getId(), generation, alive.size(), rounds, turns);
            }
        } finally {
            pool.shutdownNow();
        }

        List<BalanceOracleRules.Build> ladder = new ArrayList<>();
        byKey.forEach((key, traits) -> ladder.add(
                rules.measured(traits, cost(traits), results.getOrDefault(key, List.of()))));
        return rules.read(ladder, prices(), generation);
    }

    /**
     * Одна партия прогона, поставленная в очередь пачки.
     *
     * @param table места за столом: номера сборок из {@code current}
     * @param seed  зерно партии — оно выводится из номера партии и от числа потоков не
     *              зависит, поэтому пачка играет ровно те же партии, что играл бы
     *              последовательный прогон
     */
    private Future<?> submit(BalanceRunEntity run, int turns, List<Integer> table,
                             List<List<String>> current, long seed,
                             Map<String, List<Double>> results, Map<String, List<String>> byKey,
                             AtomicInteger played, ExecutorService pool) {
        return pool.submit(() -> {
            List<List<String>> seated = table.stream().map(current::get).toList();
            // Империй в партии ровно столько, сколько за стол село: скажи «восемь», когда
            // сборок четыре, — и партия доберёт четырёх ИИ готовыми расами, а доля сборок
            // будет меряться относительно них.
            // Место под партию — то же правило, что у замера: оракул грузит машину
            // сильнее всех, и днём его надо придержать (п. 2 этапа 2).
            load.take();
            List<BalanceVerdictRules.Empire> measured;
            try {
                measured = games.playOnce(
                        run.getGalaxySize(), seated.size(), turns, seated, seed);
            } finally {
                load.release();
            }
            synchronized (results) {
                for (BalanceVerdictRules.Empire empire : measured) {
                    String key = key(empire.traits());
                    results.computeIfAbsent(key, one -> new ArrayList<>())
                            .add(verdictRules.value(empire, BalanceVerdictRules.Yardstick.TOTAL));
                    byKey.putIfAbsent(key, List.copyOf(empire.traits()));
                }
            }
            progress.played(run.getId(), played.incrementAndGet());
        });
    }

    /**
     * Остаток прогона, когда поле уже перестало меняться — п. 3.
     * <p>
     * <b>Зачем.</b> Отсев упирается в пол из {@link #FINAL_TABLES} столов, и дальше состав
     * финалистов не меняется НИ РАЗУ: весь остаток партий уходит им, чтобы их сила стала
     * точной. А цикл при этом всё равно шёл «поколениями» по два стола с барьером между
     * ними — и барьер этот не делал ничего: {@code alive} не трогался, порядок сборок
     * пересчитывался и тут же выбрасывался. На прогоне в двести партий так проходило
     * около девяноста поколений из ста: две партии разом на двенадцатиядерной машине, а
     * между ними ожидание самой медленной из двух. Замер посреди прогона показал занятыми
     * ядро с четвертью из двенадцати.
     * <p>
     * <b>Почему это не меняет ответа.</b> Жребий тасуется здесь СТОЛЬКО ЖЕ РАЗ и в том же
     * порядке ({@code deal} на каждое несостоявшееся поколение), номера партий идут подряд,
     * а зерно партии выводится из номера — значит, играются ровно те же партии с теми же
     * соседями, и счётчик поколений приходит к тому же числу. Меняется только то, что их
     * больше не ждут по две. Прогоны остаются сравнимы с прежними.
     *
     * @return номер поколения, до которого дошёл прогон
     */
    private int playFloor(BalanceRunEntity run, List<List<String>> alive, int seats,
                          int generation, int rounds, Random random,
                          Map<String, List<Double>> results, Map<String, List<String>> byKey,
                          AtomicInteger played, ExecutorService pool) {
        List<Future<?>> playing = new ArrayList<>();
        List<List<String>> current = List.copyOf(alive);
        while (rounds < run.getGames()) {
            int turns = rules.turns(run.getTurns(), generation, MAX_TURNS);
            List<List<Integer>> tables = rules.deal(alive.size(), seats, random);
            if (tables.isEmpty()) {
                break;
            }
            for (List<Integer> table : tables) {
                if (rounds >= run.getGames()) {
                    break;
                }
                rounds++;
                playing.add(submit(run, turns, table, current, run.getSeed() * 31L + rounds,
                        results, byKey, played, pool));
            }
            generation++;
        }
        log.info("Оракул сборок {}: финал, {} сборок, {} партий одной пачкой до поколения {}",
                run.getId(), alive.size(), playing.size(), generation);
        for (Future<?> one : playing) {
            join(one);
        }
        return generation;
    }

    /** Все стороны таблицы: по ним ищутся те, что в верхушку не вошли. */
    private Set<String> prices() {
        Set<String> codes = new LinkedHashSet<>();
        raceTraits.groups().forEach(group ->
                group.options().forEach(trait -> codes.add(trait.code())));
        return codes;
    }

    /** Ключ сборки — её стороны в одном и том же порядке: набор, а не список. */
    private String key(Set<String> traits) {
        return traits.stream().sorted().reduce("", (a, b) -> a + " " + b);
    }

    private Integer cost(List<String> traits) {
        return traits.stream().mapToInt(code -> raceTraits.require(code).picks()).sum();
    }

    private double mean(Map<String, List<Double>> results, List<String> build) {
        List<Double> own = results.get(key(new LinkedHashSet<>(build)));
        if (own == null || own.isEmpty()) {
            // Ещё не игравшая сборка не должна вытеснять сыгравшую: место ей в хвосте.
            return Double.NEGATIVE_INFINITY;
        }
        return own.stream().mapToDouble(Double::doubleValue).sum() / own.size();
    }

    private void join(Future<?> one) {
        try {
            one.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Поиск сборок прерван", interrupted);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException(failure.getCause());
        }
    }

    /** Ладдер в JSON — он ложится в ту же запись прогона, что и приговоры ценам. */
    public String write(BalanceOracleRules.Search search) {
        try {
            return objectMapper.writeValueAsString(search);
        } catch (com.fasterxml.jackson.core.JsonProcessingException broken) {
            throw new IllegalStateException("Не удалось записать ладдер оракула", broken);
        }
    }

    public BalanceOracleRules.Search read(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException broken) {
            throw new IllegalStateException("Не удалось прочитать ладдер оракула", broken);
        }
    }

    /** Идентификатор прогона нужен обёртке, которая заводит запись и пускает поиск. */
    public UUID id(BalanceRunEntity run) {
        return run.getId();
    }
}
