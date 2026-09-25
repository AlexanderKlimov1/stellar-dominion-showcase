package com.moo3.server.service;

import com.moo3.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

/**
 * Конец партии — п. 3.
 * <p>
 * Идёт <b>последней</b> (порядок 19): к этому моменту ход сосчитан весь — захваты,
 * колонии, население и отношения, — и объявлять победу можно по окончательному
 * состоянию галактики, а не по промежуточному.
 * <p>
 * После летописи (18) — чтобы последний ход попал в график окна «Инфо»: партия, у которой
 * не записан её же последний ход, читалась бы как оборванная.
 */
@Service
public class VictoryPhase implements TurnPhase {

    private final VictoryService victoryService;

    public VictoryPhase(VictoryService victoryService) {
        this.victoryService = victoryService;
    }

    @Override
    public Integer order() {
        return 19;
    }

    @Override
    public String name() {
        return "Конец партии";
    }

    @Override
    public void apply(TurnContext context) {
        victoryService.check(context);
    }
}
