package com.moo3.server.service;

import com.moo3.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

/**
 * Фаза встречи флотов в конце хода — п. 8.
 * <p>
 * Идёт последней из боевых: к этому моменту колонии уже построили корабли этого хода, а
 * шпионы отработали. Флоты, оказавшиеся в одной системе, видят друг друга, и в начале
 * следующего хода игрокам предлагается выбор — атаковать или разойтись.
 */
@Service
public class EncounterPhase implements TurnPhase {

    private final EncounterService encounterService;

    public EncounterPhase(EncounterService encounterService) {
        this.encounterService = encounterService;
    }

    @Override
    public Integer order() {
        return 14;
    }

    @Override
    public String name() {
        return "Встречи флотов";
    }

    @Override
    public void apply(TurnContext context) {
        encounterService.detect(context);
    }
}
