package com.moo3.server.galaxy;

import com.moo3.server.domain.ColonyProject;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.enums.RaceEffectType;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.MineralRichness;
import com.moo3.server.domain.enums.PlanetClimate;
import com.moo3.server.domain.enums.PlanetSize;
import com.moo3.server.service.PopulationCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Раздача стартовых систем игрокам.
 * <p>
 * Системы выбираются методом наибольшего удаления: каждая следующая родная система
 * ставится как можно дальше от уже занятых, чтобы восемь игроков (п. 3.2) не оказались
 * в одном углу галактики. Звезда Orion под старт не отдаётся — это особая система (п. 4.2.1).
 *
 * <p><b>Стартовые системы у всех империй ОДИНАКОВЫ</b> — и у людей, и у ИИ. Чертёж
 * тянется один на партию и накладывается на каждую родную звезду: те же орбиты, те же
 * размеры, климаты и недра. Разным остаётся только родной мир, и ровно настолько,
 * насколько его правит раса (большой, богатый, бедный мир и климат справочной расы).
 *
 * <p>Причина не в красоте, а в измеримости. Всё, ради чего игра считается прогонами
 * (этапы 0–2, `balance-metrics-works.txt`), — это ответ на вопрос «сколько стоит сторона
 * расы». Случайная стартовая система отвечает на него шумом: соседняя планета-гигант под
 * газовый комбайн или пустая система из одного астероидного пояса двигают выработку
 * сильнее иной стороны расы за десять очков. Ковариаты стартового угла (планеты рядом,
 * расстояние до соседа) убирают разницу МЕЖДУ звёздами, но не внутри родной системы.
 * Одинаковый старт убирает её целиком — и заодно делает партию честной: игрок и ИИ
 * начинают с одного и того же.
 */
@Component
public class HomeworldAllocator {

    private static final Logger log = LoggerFactory.getLogger(HomeworldAllocator.class);

    /** Стартовое население родной планеты. */
    private static final Integer START_POPULATION = 8;

    /**
     * Размер родного мира по умолчанию — п. 4.1, п. 7.
     * <p>
     * <b>СРЕДНИЙ</b>, как в MOO II: там всякая раса начинает на среднем земном мире, а
     * сторона расы «большой мир» делает его БОЛЬШИМ. Здесь по умолчанию стоял большой — и
     * сторона, сдвигающая размер на ступень, выдавала ОГРОМНЫЙ мир, которого в
     * конструкторе оригинала нет вовсе. Ошибка тихая: партия шла, ничего не падало, просто
     * каждая империя начинала богаче положенного, а купившая сторону — вдвойне.
     * <p>
     * Для балансировки это било прямо по замерам: вместимость родного мира это население,
     * а население — выработка и наука, то есть два главных мерила из семи. Все прежние
     * цены сняты с завышенной базы.
     */
    private static final PlanetSize HOMEWORLD_SIZE = PlanetSize.MEDIUM;
    private static final MineralRichness HOMEWORLD_MINERALS = MineralRichness.AVERAGE;

    private final PopulationCalculator populationCalculator;
    private final PlanetGenerator planetGenerator;

    public HomeworldAllocator(PopulationCalculator populationCalculator,
                              PlanetGenerator planetGenerator) {
        this.populationCalculator = populationCalculator;
        this.planetGenerator = planetGenerator;
    }

    /**
     * Назначает каждому игроку родную систему и превращает одну из её планет в родной мир.
     *
     * @param homeClimateByRaceCode климат родной планеты по коду расы (п. 7). Своей
     *                               особенности на климат в конструкторе MOO II нет —
     *                               там любой родной мир земного типа, и климат приходит
     *                               только отсюда, из справочной расы
     * @param raceEffectsByPlayer    что раса игрока делает с его родным миром (п. 7):
     *                               большой, богатый или бедный мир
     */
    public void allocate(List<StarSystemEntity> systems,
                         List<PlayerEntity> players,
                         Map<String, PlanetClimate> homeClimateByRaceCode,
                         Map<UUID, RaceEffects> raceEffectsByPlayer,
                         Random random) {

        List<StarSystemEntity> candidates = systems.stream()
                .filter(system -> !system.getSpecial())
                .toList();

        if (candidates.size() < players.size()) {
            log.warn("Систем под старт {} меньше, чем игроков {}", candidates.size(), players.size());
        }

        List<StarSystemEntity> chosen = pickSpreadOut(candidates, players.size(), random);

        // Чертёж стартовой системы — ОДИН на партию: см. javadoc класса.
        List<PlanetGenerator.PlanetSpec> layout = startingLayout(random);

        for (int i = 0; i < chosen.size(); i++) {
            PlayerEntity player = players.get(i);
            StarSystemEntity system = chosen.get(i);
            PlanetClimate climate =
                    homeClimateByRaceCode.getOrDefault(player.getRaceCode(), PlanetClimate.TERRAN);

            // Чудище с родной звезды убирается: галактика заселяет их вслепую, а империя,
            // запертая сторожем с первого хода, — это не трудность, а испорченная партия
            // (п. 11.1). Соседние системы оно стережёт на здоровье.
            system.setMonster(null);
            system.setMonsterStrength(null);

            applyLayout(system, layout);
            makeHomeworld(pickHomeworldSlot(system), player, climate,
                    raceEffectsByPlayer.getOrDefault(player.getId(), RaceEffects.NONE));
            player.setHomeSystemId(system.getId());
            renameHomeSystem(systems, system, player.getHomeStarName());
        }
    }

    /**
     * Сколько раз тянуть чертёж стартовой системы, добиваясь места под вторую колонию.
     * <p>
     * Пока чертёж был у каждой империи свой, пустая родная система била по одной из них.
     * Теперь он ОДИН НА ВСЕХ, и пустой бьёт по всем разом: партия, где ни у кого нет места
     * под вторую колонию, стоит на месте — на 300 ходах четыре империи из восьми так и
     * остались с одной колонией и без флота. Это не замер баланса, а замер застрявшей игры.
     * <p>
     * Поэтому чертёж тянется, пока в нём не окажется хотя бы двух пригодных к колонизации
     * планет: одна станет родным миром, вторая — тем, ради чего строят колониальный
     * корабль. В MOO II родная система тоже не бывает пустой. Если жребий столько раз
     * подряд выдаёт пустое, берётся последнее вытянутое: партия без второй планеты всё
     * же лучше, чем партия без галактики.
     */
    private static final int LAYOUT_ATTEMPTS = 40;

    /** Чертёж стартовой системы: общий на всех и с местом под вторую колонию. */
    private List<PlanetGenerator.PlanetSpec> startingLayout(Random random) {
        List<PlanetGenerator.PlanetSpec> layout = planetGenerator.layout(random);
        for (int attempt = 0; attempt < LAYOUT_ATTEMPTS && habitable(layout) < 2; attempt++) {
            layout = planetGenerator.layout(random);
        }
        return layout;
    }

    /** Сколько в чертеже планет, на которых вообще можно жить. */
    private long habitable(List<PlanetGenerator.PlanetSpec> layout) {
        return layout.stream().filter(spec -> spec.climate().getColonizable()).count();
    }

    /**
     * Приводит родную систему к общему чертежу.
     * <p>
     * Планеты правятся на месте, а лишние удаляются и недостающие добавляются: система к
     * этому времени уже записана, и подменять её планеты целиком значило бы удалять и
     * вставлять строки на каждой родной звезде. Связь помечена {@code orphanRemoval}, так
     * что снятая с системы планета уходит из базы сама.
     */
    private void applyLayout(StarSystemEntity system, List<PlanetGenerator.PlanetSpec> layout) {
        List<PlanetEntity> planets = system.getPlanets();
        while (planets.size() > layout.size()) {
            planets.removeLast();
        }
        for (int orbit = 0; orbit < layout.size(); orbit++) {
            if (orbit < planets.size()) {
                planetGenerator.apply(planets.get(orbit), system.getName(), layout.get(orbit));
            } else {
                system.addPlanet(planetGenerator.planet(system.getName(), layout.get(orbit)));
            }
        }
    }

    /**
     * Родная звезда получает название, выбранное игроком при входе в игру.
     * <p>
     * Имя приходит из запроса, поэтому его может не быть вовсе — тогда система остаётся
     * с именем из справочника. Совпасть с уже выданным именем оно тоже может: в этом
     * случае системы меняются названиями, и двух одинаковых в галактике не появляется.
     */
    private void renameHomeSystem(List<StarSystemEntity> systems, StarSystemEntity home, String name) {
        if (name == null) {
            return;
        }

        String previous = home.getName();
        systems.stream()
                .filter(system -> system != home && system.getName().equals(name))
                .findFirst()
                .ifPresent(system -> system.rename(previous));
        home.rename(name);
        log.debug("Родная звезда игрока переименована: {} → {}", previous, name);
    }

    /** Родные системы разносим по галактике: жадный выбор самой удалённой точки. */
    private List<StarSystemEntity> pickSpreadOut(List<StarSystemEntity> candidates, Integer count, Random random) {
        int limit = Math.min(count, candidates.size());
        List<StarSystemEntity> chosen = new ArrayList<>(limit);
        if (limit == 0) {
            return chosen;
        }

        List<StarSystemEntity> pool = new ArrayList<>(candidates);
        chosen.add(pool.remove(random.nextInt(pool.size())));

        Map<StarSystemEntity, Double> minDistance = new HashMap<>();
        for (StarSystemEntity system : pool) {
            minDistance.put(system, distanceSquared(system, chosen.getFirst()));
        }

        while (chosen.size() < limit) {
            StarSystemEntity best = pool.getFirst();
            for (StarSystemEntity system : pool) {
                if (minDistance.get(system) > minDistance.get(best)) {
                    best = system;
                }
            }
            pool.remove(best);
            minDistance.remove(best);
            chosen.add(best);

            for (StarSystemEntity system : pool) {
                minDistance.merge(system, distanceSquared(system, best), Math::min);
            }
        }
        return chosen;
    }

    /**
     * Планета под родной мир: берём первую орбиту, пригодную к колонизации,
     * иначе — самую первую планету системы, её характеристики всё равно переписываются.
     * <p>
     * Чертёж у всех стартовых систем один, поэтому и орбита родного мира у всех империй
     * выходит одна и та же — выбирать её отдельным правилом не нужно.
     */
    private PlanetEntity pickHomeworldSlot(StarSystemEntity system) {
        return system.getPlanets().stream()
                .filter(planet -> planet.getClimate().getColonizable())
                .findFirst()
                .orElseGet(() -> system.getPlanets().getFirst());
    }

    private void makeHomeworld(PlanetEntity planet, PlayerEntity player, PlanetClimate climate, RaceEffects race) {
        // Родной мир правит раса — п. 7: большой мир на ступень крупнее обычного, богатый
        // и бедный — на ступень богаче и беднее минералами. Вместимость планеты пишется
        // без расовых прибавок: подземные и неприхотливые считают своё поверх записанного,
        // и колония может сменить хозяина вместе с его расой.
        PlanetSize size = shift(PlanetSize.values(), HOMEWORLD_SIZE.ordinal(), race.amount(RaceEffectType.HOME_SIZE_STEPS));
        MineralRichness minerals = shift(MineralRichness.values(), HOMEWORLD_MINERALS.ordinal(),
                race.amount(RaceEffectType.HOME_MINERALS_STEPS));

        planet.setPlanetSize(size);
        planet.setClimate(climate);
        planet.setMinerals(minerals);
        planet.setMaxPopulation(populationCalculator.maxPopulation(size, climate));
        planet.setPopulation(Math.min(START_POPULATION, planet.getMaxPopulation()));
        // Родной мир встаёт с распределением по умолчанию: сначала кормит себя, остаток
        // делит между производством и наукой. Дальше распределение — дело игрока (п. 4.1).
        planet.setJobs(populationCalculator.defaultJobs(climate, planet.getPopulation()));
        // Колония без стройки производит впустую, поэтому родной мир начинает с товаров —
        // как в MOO II, где это стройка «на безрыбье»: деньги нужны всегда (п. 10).
        planet.setProjectCode(ColonyProject.TRADE_GOODS);
        planet.setOwnerPlayerId(player.getId());
        planet.setHomeworld(Boolean.TRUE);
    }

    /**
     * Ступень вверх или вниз по шкале размеров и богатств — п. 7.
     * <p>
     * Обе шкалы перечислены по возрастанию, поэтому «на ступень крупнее» это соседняя
     * запись, а край шкалы просто останавливает сдвиг: крупнее огромной планеты ничего
     * нет. Шаг здесь один, тогда как в MOO II богатый мир поднимает выработку рабочего
     * сразу на две единицы — но и шкала богатств там длиннее здешних пяти ступеней.
     */
    private <T> T shift(T[] scale, int from, Integer steps) {
        return scale[Math.clamp((long) from + steps, 0, scale.length - 1)];
    }

    /** Квадрат расстояния в парсеках: корень для сравнения не нужен. */
    private Double distanceSquared(StarSystemEntity a, StarSystemEntity b) {
        double dx = a.getXParsec() - b.getXParsec();
        double dy = a.getYParsec() - b.getYParsec();
        return dx * dx + dy * dy;
    }
}
