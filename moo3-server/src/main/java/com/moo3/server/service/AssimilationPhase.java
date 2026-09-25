package com.moo3.server.service;

import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.service.stub.TurnPhase;
import org.springframework.stereotype.Component;

/**
 * Перевоспитание подданных — п. 7, п. 12: фаза конца хода.
 * <p>
 * Захваченная колония достаётся победителю с людьми, и люди эти ещё чужие: работают по
 * правилам своей прежней расы (см. {@code ColonyService.ColonyContext.effects}). Каждый
 * ход колония копит ходы ассимиляции, и как только их набирается на жителя, одним
 * подданным становится меньше, а своих — больше. Скорость задаёт строй победителя и его
 * обхождение с чужими — {@link AssimilationRules}.
 * <p>
 * Место в ходу — порядок 11, между исследованиями и разведкой: свободный номер в цепочке
 * фаз, а к самому счёту ассимиляция ни одну из соседних фаз не привязана — подданный,
 * ставший своим, работает как свой со следующего хода.
 */
@Component
public class AssimilationPhase implements TurnPhase {

    private final AssimilationRules rules;

    public AssimilationPhase(AssimilationRules rules) {
        this.rules = rules;
    }

    @Override
    public Integer order() {
        return 11;
    }

    @Override
    public String name() {
        return "Ассимиляция подданных";
    }

    @Override
    public void apply(TurnContext context) {
        for (PlanetEntity colony : context.colonies()) {
            if (colony.getAlienPopulation() <= 0) {
                colony.setAlienOwnerPlayerId(null);
                continue;
            }

            Integer turns = rules.turnsPerColonist(
                    context.colonyContext().race(colony));
            Integer points = colony.getAssimilationPoints() + 1;
            if (points < turns) {
                colony.setAssimilationPoints(points);
                continue;
            }

            colony.setAssimilationPoints(0);
            colony.setAlienPopulation(colony.getAlienPopulation() - 1);
            if (colony.getAlienPopulation() <= 0) {
                colony.setAlienOwnerPlayerId(null);
                context.report().add(colony.getOwnerPlayerId(), "ASSIMILATION",
                        new MessageKey("turn.assimilation.done", colony.getName()),
                        context.systemOf(colony), colony.getId());
            }
        }
    }
}
