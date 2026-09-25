package com.moo3.server.service;

import com.moo3.server.domain.RaceTrait;
import com.moo3.server.domain.RaceTraitGroup;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Случайная законная сборка расы ровно на заданный бюджет — этап 2 плана
 * (`balance-metrics-works.txt`).
 * <p>
 * Ценности сторон вынимаются регрессией по случайным сборкам, а не перебором «по одной
 * стороне за раз»: стороны взаимодействуют, и перебор по одной и медленный, и слепой к
 * связкам. Поэтому генератор обязан выдавать именно <b>законные</b> сборки — те же, что
 * собрал бы игрок: не больше одной стороны из группы-переключателя, без несовместимых пар
 * и ровно на бюджет.
 *
 * <p><b>Сборка умеет продавать слабости — п. 2.1 этапа 2.</b> Сперва генератор брал одни
 * плюсовые стороны, и это оставляло слепое пятно на всей минусовой половине таблицы:
 * отталкивающие, феодализм, малая тяжесть, неизобретательность — четырнадцать сторон из
 * пятидесяти трёх не попадали в замер НИ РАЗУ, и цену им нечем было проверить. Теперь
 * сборка сперва решает, сколько очков вернуть слабостями (от нуля до потолка
 * анти-выбора), и тратит бюджет вместе с вырученным.
 *
 * <p><b>Возврат выбирается ЗАРАНЕЕ, а не жадно по дороге.</b> Жадный набор брал бы
 * слабости всегда — они ведь только освобождают очки, — и почти в каждой сборке возврат
 * упирался бы в потолок. Замер тогда сравнивал бы не «со слабостью и без», а «с этой
 * слабостью и с той». Заданная заранее цель возврата даёт ровный разброс, включая сборки
 * вовсе без слабостей.
 *
 * <p><b>Бесплатное добирается в конце</b> — там, где группа осталась незанятой. Так в
 * сборке всегда есть строй: в конструкторе он есть у каждой расы, диктатура просто не
 * стоит ничего. Без этого сборка на ноль очков оказывалась расой вовсе без строя —
 * состоянием, в котором игрок не бывает, и замер относительно него был нечестным.
 */
@Service
public class RaceBuildGenerator {

    /** Сколько раз пробовать собрать бюджет, прежде чем сдаться. */
    private static final int ATTEMPTS = 200;

    private final RaceTraitCatalog catalog;

    public RaceBuildGenerator(RaceTraitCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Сборка ровно на {@code budget} очков, без слабостей.
     * <p>
     * Остаётся для случаев, где сравнивать надо только траты: курс «очко → сила» меряется
     * потраченным, и сборка со слабостями сместила бы его.
     */
    public List<String> build(Integer budget, Random random) {
        return build(budget, 0, random);
    }

    /**
     * Сборка ровно на {@code budget} очков, с возвратом до {@code antiBudget} очков
     * слабостями.
     *
     * @return коды сторон; пусто — такой набор не собрался (бывает на странных сочетаниях
     *         бюджета и возврата; для замера это просто пропущенная сборка, а не отказ)
     */
    public List<String> build(Integer budget, Integer antiBudget, Random random) {
        return build(budget, antiBudget, random, List.of(), List.of());
    }

    /**
     * Сборка, в которой названные стороны стоят обязательно, а названные — не стоят вовсе
     * (п. 2.14 этапа 2: <b>подсаженная связка</b>).
     * <p>
     * Зачем это нужно. Связку двух сторон случайная сборка поймать не может, и это не
     * вопрос длины прогона: киборгов взяли 104 сборки из 3200, великих промышленников — 61,
     * и вместе они сойдутся достаточно часто не раньше, чем прогон вырастет на порядок.
     * Значит, пару нужно ПОДСАЖИВАТЬ: часть сборок несёт обе стороны, часть — только одну,
     * часть — ни одной. Кто в какую четверть попал, решает не генератор, а тот, кто его
     * зовёт: генератору называют, что взять и чего не брать.
     *
     * @param required стороны, которые в сборке будут обязательно
     * @param forbidden стороны, которых в сборке не будет
     * @return коды сторон; пусто — такой набор не собрался
     */
    public List<String> build(Integer budget, Integer antiBudget, Random random,
                              List<String> required, List<String> forbidden) {
        Set<String> outside = new HashSet<>(forbidden);
        // Обязательные тоже вон из списков выбора: группа-переключатель их и так не пустит
        // дважды, а особую способность из группы с multiple — пустила бы.
        outside.addAll(required);

        List<Option> priced = new ArrayList<>();
        List<Option> weak = new ArrayList<>();
        List<Option> free = new ArrayList<>();
        List<Option> must = new ArrayList<>();
        for (RaceTraitGroup group : catalog.groups()) {
            for (RaceTrait trait : group.options()) {
                if (required.contains(trait.code())) {
                    must.add(new Option(group, trait));
                    continue;
                }
                if (outside.contains(trait.code())) {
                    continue;
                }
                if (trait.picks() > 0) {
                    priced.add(new Option(group, trait));
                } else if (trait.picks() < 0) {
                    weak.add(new Option(group, trait));
                } else {
                    free.add(new Option(group, trait));
                }
            }
        }
        if (must.size() != required.size()) {
            return List.of();
        }

        // Что обязательные стоят сами: плюсовые тратят бюджет, минусовые занимают часть
        // потолка анти-выбора ещё до всякого жребия.
        int spent = must.stream().mapToInt(one -> Math.max(0, one.trait().picks())).sum();
        int given = must.stream().mapToInt(one -> Math.max(0, -one.trait().picks())).sum();
        // НАДБАВКА ЗА СВЯЗКУ обязательных вычитается из бюджета ЗАРАНЕЕ — п. 7 (журнал,
        // п. 3.71). Иначе подсадка связки не собрала бы ни одной сборки: обязательные
        // стороны тратят бюджет ровно, добор заполняет остаток до копейки, и надбавка,
        // всплывшая последней, уводила бы КАЖДУЮ попытку за бюджет.
        spent += catalog.combinationExtra(
                must.stream().map(one -> one.trait().code()).toList());
        if (given > antiBudget) {
            return List.of();
        }

        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Pick pick = new Pick();
            if (!place(pick, must)) {
                // Обязательные не уживаются между собой — пробовать нечего.
                return List.of();
            }
            // Сколько вернуть слабостями — решается один раз на сборку и ровно, а не
            // «сколько получится»: иначе разброс возвратов сложился бы в один столбик у
            // потолка. Возврат обязательных уже сделан, добираем сверх него.
            int refund = given + random.nextInt(antiBudget - given + 1);
            if (refund > given && !fill(pick, weak, given - refund, random)) {
                // Такой возврат ровно не набрался — пробуем другой, а не сдаёмся: у
                // слабостей цены редкие, и не всякая сумма из них складывается.
                continue;
            }
            int left = budget + refund - spent;
            if (left < 0) {
                // Обязательные дороже бюджета с этим возвратом: может помочь возврат
                // побольше, поэтому не сдаёмся, а пробуем снова.
                continue;
            }
            if (left == 0) {
                List<String> built = withFree(pick, free);
                if (fits(built, budget)) {
                    return built;
                }
                continue;
            }
            if (fill(pick, priced, left, random)) {
                List<String> built = withFree(pick, free);
                if (fits(built, budget)) {
                    return built;
                }
            }
        }
        return List.of();
    }

    /** Ставит обязательные стороны; {@code false} — они не уживаются друг с другом. */
    private Boolean place(Pick pick, List<Option> must) {
        for (Option option : must) {
            if (!Boolean.TRUE.equals(option.group().multiple())
                    && pick.groups().contains(option.group().code())) {
                return Boolean.FALSE;
            }
            if (incompatible(pick.traits(), option.trait())) {
                return Boolean.FALSE;
            }
            pick.traits().add(option.trait());
            pick.groups().add(option.group().code());
        }
        return Boolean.TRUE;
    }

    /**
     * Набирает из {@code options} ровно {@code target} очков (по модулю — и для трат, и
     * для возврата), не нарушая правил конструктора.
     *
     * @return набралось ли ровно столько
     */
    private Boolean fill(Pick pick, List<Option> options, int target, Random random) {
        List<Option> shuffled = new ArrayList<>(options);
        Collections.shuffle(shuffled, random);
        int sum = 0;
        for (Option option : shuffled) {
            int picks = option.trait().picks();
            // Перебор считается по модулю: траты растут вверх, возврат вниз.
            if (Math.abs(sum + picks) > Math.abs(target)) {
                continue;
            }
            if (!Boolean.TRUE.equals(option.group().multiple())
                    && pick.groups().contains(option.group().code())) {
                continue;
            }
            if (incompatible(pick.traits(), option.trait())) {
                continue;
            }
            pick.traits().add(option.trait());
            pick.groups().add(option.group().code());
            sum += picks;
            if (sum == target) {
                return Boolean.TRUE;
            }
        }
        // Не набралось ровно. Убирать взятое незачем: каждая попытка начинает с нового
        // набора, а недобранный этот будет выброшен вместе с ним.
        return Boolean.FALSE;
    }

    /** Запрет двусторонний и объявлен обычно у одной из сторон — смотрим в обе стороны. */
    private Boolean incompatible(List<RaceTrait> chosen, RaceTrait candidate) {
        for (RaceTrait already : chosen) {
            if (candidate.excludes().contains(already.code())
                    || already.excludes().contains(candidate.code())) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    /**
     * Укладывается ли собранное в бюджет ВМЕСТЕ с надбавками за связки — п. 7.
     * <p>
     * Связку могут собрать и сами жребии добора, а не только обязательные стороны: такую
     * сборку остаётся лишь отбросить и попробовать снова. Надбавка обязательных вычтена
     * заранее, поэтому здесь отсеиваются считанные попытки, а не все подряд.
     */
    private Boolean fits(List<String> built, Integer budget) {
        int total = built.stream().mapToInt(code -> catalog.require(code).picks()).sum();
        return total + catalog.combinationExtra(built) <= budget;
    }

    private List<String> withFree(Pick pick, List<Option> free) {
        List<String> codes = new ArrayList<>(pick.traits().stream().map(RaceTrait::code).toList());
        for (Option option : free) {
            if (!pick.groups().contains(option.group().code())) {
                codes.add(option.trait().code());
            }
        }
        return codes;
    }

    /** Что уже набрано: стороны и занятые ими группы-переключатели. */
    private record Pick(List<RaceTrait> traits, Set<String> groups) {
        Pick() {
            this(new ArrayList<>(), new HashSet<>());
        }
    }

    private record Option(RaceTraitGroup group, RaceTrait trait) {
    }
}
