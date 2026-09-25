package com.moo3.server.galaxy;

import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.MineralRichness;
import com.moo3.server.domain.enums.PlanetClimate;
import com.moo3.server.domain.enums.PlanetFind;
import com.moo3.server.domain.enums.PlanetSize;
import com.moo3.server.service.PopulationCalculator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Генерация планет звёздной системы — п. 3 и п. 4.1.
 * <p>
 * В системе от 1 до 5 планет. Размер, плодородность и богатство минералами
 * выбираются по весам: чем комфортнее планета, тем реже она встречается.
 */
@Component
public class PlanetGenerator {

    private static final Integer MIN_PLANETS = 1;
    private static final Integer MAX_PLANETS = 5;

    private static final List<Weighted<PlanetSize>> SIZE_WEIGHTS = List.of(
            new Weighted<>(PlanetSize.TINY, 15),
            new Weighted<>(PlanetSize.SMALL, 25),
            new Weighted<>(PlanetSize.MEDIUM, 30),
            new Weighted<>(PlanetSize.LARGE, 20),
            new Weighted<>(PlanetSize.HUGE, 10));

    private static final List<Weighted<PlanetClimate>> CLIMATE_WEIGHTS = List.of(
            new Weighted<>(PlanetClimate.ASTEROID_BELT, 12),
            new Weighted<>(PlanetClimate.GAS_GIANT, 12),
            new Weighted<>(PlanetClimate.TOXIC, 10),
            new Weighted<>(PlanetClimate.RADIATED, 10),
            new Weighted<>(PlanetClimate.BARREN, 9),
            new Weighted<>(PlanetClimate.DESERT, 8),
            new Weighted<>(PlanetClimate.TUNDRA, 8),
            new Weighted<>(PlanetClimate.ARID, 7),
            new Weighted<>(PlanetClimate.SWAMP, 7),
            new Weighted<>(PlanetClimate.OCEAN, 7),
            new Weighted<>(PlanetClimate.TERRAN, 6),
            new Weighted<>(PlanetClimate.GAIA, 4));

    private static final List<Weighted<MineralRichness>> MINERAL_WEIGHTS = List.of(
            new Weighted<>(MineralRichness.ULTRA_POOR, 10),
            new Weighted<>(MineralRichness.POOR, 20),
            new Weighted<>(MineralRichness.RICH, 25),
            new Weighted<>(MineralRichness.AVERAGE, 30),
            new Weighted<>(MineralRichness.ULTRA_RICH, 15));

    private final PopulationCalculator populationCalculator;

    public PlanetGenerator(PopulationCalculator populationCalculator) {
        this.populationCalculator = populationCalculator;
    }

    /**
     * Чертёж планеты: что за мир стоит на орбите. Именно чертежами задаётся СТАРТОВАЯ
     * система — один на партию для всех империй разом (см. {@link #layout}).
     */
    public record PlanetSpec(Integer orbit, PlanetSize size, PlanetClimate climate,
                             MineralRichness minerals) {
    }

    /**
     * Наполняет систему планетами и раскладывает по ним находки — п. 4.1.
     * <p>
     * <b>Находка бросается ЗДЕСЬ, а не в чертеже</b> ({@link #layout}): чертёж стартовой
     * системы один на всю партию, и золотая жила в нём досталась бы каждой империи разом.
     * Родная звезда находок не получает вовсе — {@link #apply} их снимает, как
     * {@code HomeworldAllocator} снимает со стартовой звезды чудищ: империя, начинающая
     * партию с самоцветами под боком, — это не трудность и не удача, а испорченный замер.
     */
    public void populate(StarSystemEntity system, Random random) {
        for (PlanetSpec spec : layout(random)) {
            PlanetEntity planet = planet(system.getName(), spec);
            planet.setFind(find(planet.getMaxPopulation(), random));
            system.addPlanet(planet);
        }
    }

    /**
     * Что нашлось на планете такой вместимости; {@code null} — ничего, и так у
     * подавляющего большинства планет (доля — {@link PlanetFind#SHARE_PERCENT}).
     * <p>
     * Годность планеты смотрится ДО жребия о виде находки: на газовом гиганте золото —
     * это находка, которой никто никогда не воспользуется, селиться там нельзя.
     */
    private PlanetFind find(Integer maxPopulation, Random random) {
        boolean lucky = random.nextInt(100) < PlanetFind.SHARE_PERCENT;
        List<Weighted<PlanetFind>> fitting = PlanetFind.all().stream()
                .filter(one -> one.fits(maxPopulation))
                .map(one -> new Weighted<>(one, one.getWeight()))
                .toList();
        PlanetFind rolled = fitting.isEmpty() ? null : pick(fitting, random);
        return lucky ? rolled : null;
    }

    /**
     * Чертёж системы: сколько в ней планет и какие.
     * <p>
     * Вынесен отдельно ради стартовых систем: они у всех империй ОДИНАКОВЫ, и чертёж для
     * них тянется один на партию, а потом накладывается на каждую родную звезду
     * ({@code HomeworldAllocator}).
     */
    public List<PlanetSpec> layout(Random random) {
        int count = MIN_PLANETS + random.nextInt(MAX_PLANETS - MIN_PLANETS + 1);
        List<PlanetSpec> specs = new ArrayList<>(count);
        for (int orbit = 1; orbit <= count; orbit++) {
            specs.add(new PlanetSpec(orbit, pick(SIZE_WEIGHTS, random),
                    pick(CLIMATE_WEIGHTS, random), pick(MINERAL_WEIGHTS, random)));
        }
        return specs;
    }

    /** Планета по чертежу: пустая, необитаемая, с посчитанной вместимостью. */
    public PlanetEntity planet(String systemName, PlanetSpec spec) {
        PlanetEntity planet = new PlanetEntity();
        apply(planet, systemName, spec);
        return planet;
    }

    /**
     * Переписывает планету под чертёж — так стартовая система приводится к общему виду.
     * <p>
     * Планета правится на месте, а не заводится заново: система к этому времени уже
     * записана, и подмена строк стоила бы удаления с новой вставкой на каждой родной
     * звезде.
     */
    public void apply(PlanetEntity planet, String systemName, PlanetSpec spec) {
        planet.setOrbit(spec.orbit());
        planet.setName(StarSystemEntity.planetName(systemName, spec.orbit()));
        planet.setPlanetSize(spec.size());
        planet.setClimate(spec.climate());
        planet.setMinerals(spec.minerals());
        planet.setMaxPopulation(populationCalculator.maxPopulation(spec.size(), spec.climate()));
        planet.setPopulation(0);
        planet.setHomeworld(Boolean.FALSE);
        // Находки со стартовой системы снимаются: чертёж у всех империй общий, и находка
        // в нём досталась бы всем разом, а «у всех одинаково» тут не спасает — она сдвинет
        // весь старт партии. Так же чистится от чудищ родная звезда (HomeworldAllocator).
        planet.setFind(null);
        planet.setFindClaimed(Boolean.FALSE);
    }

    private <T> T pick(List<Weighted<T>> weights, Random random) {
        int total = weights.stream().mapToInt(Weighted::weight).sum();
        int roll = random.nextInt(total);
        for (Weighted<T> weighted : weights) {
            roll -= weighted.weight();
            if (roll < 0) {
                return weighted.value();
            }
        }
        return weights.getLast().value();
    }

    private record Weighted<T>(T value, Integer weight) {
    }
}
