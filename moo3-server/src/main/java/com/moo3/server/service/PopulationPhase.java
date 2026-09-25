package com.moo3.server.service;

import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.service.stub.TurnPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Фаза роста населения в конце хода — п. 4.1.1.
 * <p>
 * Идёт первой, как и в MOO II: там ход начинается с изменения населения, и только потом
 * считается выработка. Поэтому житель, родившийся в этот ход, работает уже в нём.
 * <p>
 * Население считается тысячами: колония прирастает каждый ход, а нового работника
 * получает, накопив целую тысячу. Новый работник встаёт на работу сам — сначала в фермеры,
 * пока колония не кормит себя, потом в рабочие; расстановку игрока это не сбивает.
 * <p>
 * Прирост считается по состоянию колонии на начало хода — по тому, что игрок видел, когда
 * ход заканчивал: и дома в стройке, и центр клонирования, и биосферы уже учтены.
 */
@Service
public class PopulationPhase implements TurnPhase {

    private static final Logger log = LoggerFactory.getLogger(PopulationPhase.class);

    /**
     * Ниже одного жителя колония не опускается: гибель колонии и вывоз жителей игра пока
     * не считает, а без них голодная колония вымирала бы безвозвратно.
     */
    private static final int MIN_POPULATION_K = PlanetEntity.POPULATION_UNIT;

    private final ColonyService colonyService;

    public PopulationPhase(ColonyService colonyService) {
        this.colonyService = colonyService;
    }

    @Override
    public Integer order() {
        return 5;
    }

    @Override
    public String name() {
        return "Рост населения";
    }

    @Override
    public void apply(TurnContext context) {
        ColonyService.ColonyContext colonies = context.colonyContext();

        for (PlanetEntity planet : context.colonies()) {
            Integer before = planet.getPopulation();
            Integer growthK = colonyService.growthK(planet, colonies);
            if (growthK == 0) {
                continue;
            }

            // ПОТОЛОК СЧИТАЕТСЯ С РАСОЙ — п. 7. Прирост его уже так и считает
            // (ColonyService.growthK), а обрезка стояла по потолку БЕЗ расы, и расовая
            // прибавка к вместимости обнулялась целиком: неприхотливые, водные и подземные
            // не получали от своей стороны ровно ничего. Хуже того, сторона выходила
            // ВРЕДНОЙ — империя ИИ считает свободное место с расой, видела его вечно и
            // держала такие колонии на бесконечной стройке домов. Замер круга 7:
            // неприхотливые −13,1 очка при цене 12, подземные −9,9 при цене 3.
            Integer capacity = colonyService.maxPopulation(
                    planet, colonies.effects(planet), colonies.race(planet));
            planet.setPopulationK(clamp(planet.getPopulationK() + growthK, capacity));
            // Занятия расставлены игроком, поэтому не пересобираются, а подправляются под
            // новое население: этим и занимается ColonyService.jobs.
            planet.setJobs(colonyService.jobs(planet));

            // Изменившимся колонию помечаем всегда: тысячи растут каждый ход, даже когда
            // целый житель ещё не набрался, и клиенту это видно в приросте.
            context.report().changed(planet.getOwnerPlayerId(), context.systemOf(planet), planet.getId());
            if (!before.equals(planet.getPopulation())) {
                // Убыль отмечаем словом: «жителей 5 → 4» и «4 → 5» отличаются одной
                // стрелкой, а голод игрок должен видеть с первого взгляда.
                context.report().add(planet.getOwnerPlayerId(), "POPULATION",
                        new MessageKey(growthK < 0
                                ? "turn.population.changedLosing"
                                : "turn.population.changed",
                                planet.getName(), before, planet.getPopulation()),
                        context.systemOf(planet), planet.getId());
                log.debug("Колония {}: жителей {} → {}", planet.getName(), before, planet.getPopulation());
            } else if (growthK < 0) {
                /*
                  Прирост отрицательный, но целого жителя колония ещё не потеряла: убыль
                  копится тысячами. Сказать об этом нужно сейчас, а не через несколько
                  ходов, когда житель пропадёт, — голод лечится до потерь, а не после.
                */
                context.report().add(planet.getOwnerPlayerId(), "POPULATION",
                        new MessageKey("turn.population.losing", planet.getName(), growthK),
                        context.systemOf(planet), planet.getId());
            }
        }
    }

    /** Больше вместимости колония не растёт, ниже одного жителя не падает. */
    private Integer clamp(Integer populationK, Integer maxPopulation) {
        int capacityK = maxPopulation * PlanetEntity.POPULATION_UNIT;
        return Math.max(MIN_POPULATION_K, Math.min(populationK, capacityK));
    }
}
