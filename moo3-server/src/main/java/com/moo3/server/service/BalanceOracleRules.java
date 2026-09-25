package com.moo3.server.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Правила оракула сборок — этап 3 плана (`balance-metrics-works.txt`).
 * <p>
 * <b>Зачем нужен второй прибор.</b> Этап 2 назначает цены, сравнивая СЛУЧАЙНЫЕ сборки, а
 * игрок случайной не играет: он ищет сильнейшую. Цены, честные в среднем по всем сборкам,
 * могут оставить в таблице одну-две комбинации, которые бьют всё остальное, — и заметить
 * это, меряя случайные сборки, нельзя в принципе: сильнейшая встречается среди них раз на
 * тысячу. Поэтому после каждой правки цен сборки ищет «взломщик», и найденное идёт в
 * следующий замер. Оценщик и взломщик работают по очереди — это обычный двойной оракул.
 *
 * <p><b>Как ищет.</b> Поколениями с отсевом: в каждом поколении сборки играют партии, худшая
 * половина отсеивается, а на её место приходят ПОТОМКИ выживших — та же сборка с одной
 * заменённой стороной. Поколение за поколением партии становятся длиннее: короткая партия
 * дёшево отсеивает заведомо слабых, а спорить за первое место нужно на длинной. Это
 * successive halving, и он здесь уместнее полного перебора: сборок на пятнадцать очков
 * миллионы, а партия стоит минуту.
 *
 * <p>Здесь — только числа и правила чтения; сами партии играет {@code BalanceOracleService}.
 */
@Service
public class BalanceOracleRules {

    /**
     * Насколько сильнейшая сборка может обгонять середину верхушки, чтобы таблица считалась
     * сбалансированной — п. 1 плана, условие «нет доминирующей сборки».
     * <p>
     * План говорит про «+3 % к доле побед», но доля побед считается только в партиях,
     * доигранных до конца, а прибор меряет долю выработки. Переводим порог в ту же меру:
     * один процентный пункт доли выработки — это примерно десятая часть того, что даёт
     * весь бюджет в пятнадцать очков (замер этапа 1: 0,45 п.п. на бюджет; этап 2 на трёх
     * мерилах: около 1,6 п.п.). Сборка, обгоняющая середину верхушки на столько, — это уже
     * не «чуть лучше», а «играть надо только так».
     */
    public static final double DOMINATION_GAP = 1.0;

    /** Сколько сборок верхушки считать «верхушкой» при проверке обоих условий плана. */
    public static final int TOP = 20;

    /**
     * Сколько сильнейших сборок должны идти вровень, чтобы таблица считалась
     * сбалансированной, — решение хозяина проекта.
     * <p>
     * <b>Баланс — это не одна ровная верхушка, а конкуренция нескольких сборок.</b> Прежде
     * приговор выносился сравнением сильнейшей с СЕРЕДИНОЙ верхушки, и это было слишком
     * строго: середина двадцати сборок включает и слабые, а разрыв с ними неизбежен в
     * любой живой игре. Требовать, чтобы лучшая сборка не отрывалась от двадцатой, значит
     * требовать, чтобы все сборки были равны, — а тогда выбор расы ничего не значит.
     * <p>
     * Правильный вопрос другой: есть ли у сильнейшей ДОСТОЙНЫЕ СОПЕРНИКИ. Если три сборки
     * идут вровень, игроку есть из чего выбирать, и на их соперничестве баланс и строится.
     * Если же одна отрывается от третьей — это не выбор, а единственный правильный ответ.
     * Отсюда и мера: разрыв считается между ПЕРВОЙ И ТРЕТЬЕЙ, а не первой и серединой.
     */
    public static final int COMPETING_BUILDS = 3;

    /**
     * Какую долю партий лидера сборка должна отыграть, чтобы её вообще ставили в верхушку.
     * <p>
     * Поиск отсевом устроен так, что сборки играют РАЗНОЕ число партий: финалисты по
     * девяносто, отсеянные в первом поколении — по одной-две. Сборка с тремя партиями и
     * ошибкой в двадцать пунктов может встать первой строкой просто по везению соседей —
     * и на первом же прогоне встала, объявив доминирующей себя, а не настоящего лидера.
     * <p>
     * Поэтому в верхушку идут только те, кто отыграл заметную долю партий лидера. Это не
     * порог «сколько партий достаточно» (его назначать не из чего), а отношение: кого
     * отсеяли рано, тот и мерился коротко — про него сказать нечего, кроме «хуже
     * выживших», а это в ладдере и так видно.
     */
    public static final double GAMES_SHARE_FOR_TOP = 0.25;

    /**
     * Одна сборка в ладдере оракула.
     *
     * @param traits   стороны сборки
     * @param budget   во сколько очков она обошлась
     * @param games    сколько партий она сыграла: у выживших их больше, и верить им можно
     *                 больше
     * @param strength средняя сила по мерилу приговоров (доля выработки, науки и разведки,
     *                 приведённая к нулю внутри партии)
     * @param error    стандартная ошибка средней
     */
    public record Build(List<String> traits, Integer budget, Integer games,
                        Double strength, Double error) {
    }

    /**
     * Итог поиска: ладдер сборок и ответ на два условия плана.
     *
     * @param ladder      сборки от сильнейшей к слабейшей
     * @param best        сила сильнейшей
     * @param median      сила середины верхушки
     * @param gap         насколько сильнейшая обгоняет ТРЕТЬЮ сборку верхушки: три
     *                    сборки вровень — это конкуренция, и баланс строится на ней;
     *                    пусто — сборок в верхушке меньше трёх, сравнивать не с чем
     * @param dominated   есть ли доминирующая сборка: {@code gap} больше допуска
     * @param deadTraits  стороны, не вошедшие НИ В ОДНУ сборку верхушки. По плану это не
     *                    «дорогая сторона», а неработающая механика или неизмеримая
     * @param unseenTraits стороны, которых поиск не пробовал ВООБЩЕ — ни в одной сборке
     *                    ладдера. Про них сказать нечего, и валить их в один список с
     *                    мёртвыми — значит выдавать пробел поиска за приговор стороне
     * @param generations сколько поколений отработал поиск
     */
    public record Search(List<Build> ladder, Double best, Double median, Double gap,
                         Boolean dominated, List<String> deadTraits, List<String> unseenTraits,
                         Integer generations) {
    }

    /**
     * Читает ладдер: есть ли доминирующая сборка и какие стороны в верхушку не попали.
     *
     * @param ladder сборки в любом порядке — здесь они и сортируются
     * @param priced все стороны таблицы: по ним и ищутся непопавшие
     */
    public Search read(List<Build> ladder, Set<String> priced, Integer generations) {
        List<Build> sorted = new ArrayList<>(ladder);
        sorted.sort(Comparator.comparing((Build one) -> -one.strength()));
        if (sorted.isEmpty()) {
            return new Search(List.of(), null, null, null, Boolean.FALSE,
                    List.of(), List.copyOf(priced), generations);
        }

        // Верхушку закрывают только те, кто отыграл заметную долю партий лидера: сборка с
        // тремя партиями и ошибкой в двадцать пунктов первой строкой — это везение соседей,
        // а не сила. В ладдере она остаётся, в приговоре — нет.
        int most = sorted.stream().mapToInt(Build::games).max().orElse(0);
        int enough = Math.max(2, (int) Math.ceil(most * GAMES_SHARE_FOR_TOP));
        List<Build> eligible = sorted.stream().filter(one -> one.games() >= enough).toList();
        if (eligible.isEmpty()) {
            eligible = sorted;
        }

        List<Build> top = eligible.subList(0, Math.min(TOP, eligible.size()));
        double best = top.getFirst().strength();
        // Середина верхушки остаётся для справки: по ней видно, насколько длинен хвост.
        double median = top.get(top.size() / 2).strength();

        // ДОМИНИРОВАНИЕ МЕРЯЕТСЯ РАЗРЫВОМ С ТРЕТЬЕЙ СБОРКОЙ — см. COMPETING_BUILDS.
        // Три сборки вровень — это конкуренция, на ней баланс и стоит; одна, оторвавшаяся
        // от третьей, — единственный правильный ответ, то есть отсутствие выбора.
        // Сборок меньше трёх — сравнивать не с чем, и такую верхушку считаем
        // доминирующей: выбора там нет по построению.
        double rival = top.size() >= COMPETING_BUILDS
                ? top.get(COMPETING_BUILDS - 1).strength()
                : top.getLast().strength();
        double gap = top.size() >= COMPETING_BUILDS ? best - rival : Double.MAX_VALUE;

        Set<String> alive = new LinkedHashSet<>();
        top.forEach(one -> alive.addAll(one.traits()));
        // Пробованные поиском — ВСЕ сборки ладдера, а не только верхушка: сторона, которую
        // поиск ни разу не поставил в сборку, не «мертва», а не проверена. Это разные
        // ответы, и смешивать их нельзя — первый про игру, второй про длину прогона.
        Set<String> tried = new LinkedHashSet<>();
        sorted.forEach(one -> tried.addAll(one.traits()));

        List<String> dead = priced.stream()
                .filter(code -> !alive.contains(code) && tried.contains(code))
                .sorted()
                .toList();
        List<String> unseen = priced.stream().filter(code -> !tried.contains(code)).sorted().toList();

        return new Search(sorted, round(best), round(median),
                gap == Double.MAX_VALUE ? null : round(gap),
                gap > DOMINATION_GAP, dead, unseen, generations);
    }

    /**
     * Потомок сборки: та же сборка с одной заменённой стороной.
     * <p>
     * Замена идёт не «на любую», а через тот же генератор законных сборок: выброшенная
     * сторона запрещается, остальные объявляются обязательными, и генератор добирает
     * бюджет заново. Поэтому потомок законен по построению — ровно бюджет, не больше
     * одной стороны из группы-переключателя, потолок анти-выбора соблюдён.
     *
     * @return стороны потомка; пусто — такой потомок не собрался, и звать надо ещё раз
     */
    public List<String> mutate(List<String> parent, RaceBuildGenerator builds,
                               Integer budget, Integer antiBudget, Random random) {
        if (parent.isEmpty()) {
            return List.of();
        }
        List<String> kept = new ArrayList<>(parent);
        String dropped = kept.remove(random.nextInt(kept.size()));
        return builds.build(budget, antiBudget, random, kept, List.of(dropped));
    }

    /** Средняя сила сборки и ошибка средней по сыгранным ею партиям. */
    public Build measured(List<String> traits, Integer budget, List<Double> results) {
        if (results.isEmpty()) {
            return new Build(traits, budget, 0, 0.0, null);
        }
        double mean = results.stream().mapToDouble(Double::doubleValue).sum() / results.size();
        Double error = null;
        if (results.size() > 1) {
            double sum = results.stream().mapToDouble(one -> (one - mean) * (one - mean)).sum();
            error = Math.sqrt(sum / (results.size() - 1) / results.size());
        }
        return new Build(traits, budget, results.size(), round(mean), round(error));
    }

    /**
     * Раздаёт сборки по партиям: каждая садится в партию со случайными соседями.
     * <p>
     * Соседи важны: сила меряется ДОЛЕЙ, то есть всегда относительно тех, кто сидит рядом.
     * Посади сильнейших вместе — и доли у них выйдут средние; посади сильнейшего со
     * слабыми — и он заберёт всё. Поэтому места тасуются на каждый круг, и за круг каждая
     * сборка играет ровно одну партию.
     *
     * @param seats сколько империй в партии
     * @return партии, каждая — список номеров сборок
     */
    public List<List<Integer>> deal(int candidates, int seats, Random random) {
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < candidates; index++) {
            order.add(index);
        }
        java.util.Collections.shuffle(order, random);
        List<List<Integer>> games = new ArrayList<>();
        for (int at = 0; at < order.size(); at += seats) {
            List<Integer> game = new ArrayList<>(order.subList(at, Math.min(at + seats, order.size())));
            // Недобранная партия сажает соседей по второму кругу: одна империя в партии
            // долей не мерится вовсе — доля её всегда сто процентов.
            int extra = 0;
            while (game.size() < seats && extra < order.size()) {
                Integer filler = order.get(extra++);
                if (!game.contains(filler)) {
                    game.add(filler);
                }
            }
            if (game.size() >= 2) {
                games.add(game);
            }
        }
        return games;
    }

    /** Сколько сборок доживает до следующего поколения: половина, но не меньше двух. */
    public int survivors(int alive) {
        return Math.max(2, alive / 2);
    }

    /** Сколько ходов играет поколение: каждое следующее вдвое длиннее предыдущего. */
    public int turns(int baseTurns, int generation, int maxTurns) {
        return Math.min(maxTurns, baseTurns << generation);
    }

    /** Сколько раз какая сторона встречается в верхушке — для пульта. */
    public Map<String, Integer> occurrences(List<Build> ladder) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        ladder.stream()
                .limit(TOP)
                .forEach(build -> build.traits()
                        .forEach(code -> counts.merge(code, 1, Integer::sum)));
        return counts;
    }

    private Double round(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }
}
