package com.moo3.server.service;

import com.moo3.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

/**
 * Летопись империй в конце хода — п. 11.1: строка на игрока для графика окна «Инфо».
 * <p>
 * Идёт <b>последней</b> (порядок 18): замер должен застать ход уже посчитанным — с
 * выросшим населением, достроенными кораблями, изученной технологией и объявленной
 * войной. Снятый раньше, он описывал бы ход, которого ещё не было.
 */
@Service
public class EmpireHistoryPhase implements TurnPhase {

    private final EmpireInfoService empireInfoService;

    public EmpireHistoryPhase(EmpireInfoService empireInfoService) {
        this.empireInfoService = empireInfoService;
    }

    @Override
    public Integer order() {
        return 18;
    }

    @Override
    public String name() {
        return "Летопись империй";
    }

    @Override
    public void apply(TurnContext context) {
        empireInfoService.record(context);
    }
}
