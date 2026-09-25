package com.moo3.server.service;

import com.moo3.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

/**
 * Случайные галактические события конца хода — п. 11.1.
 * <p>
 * В MOO II ход время от времени приносит новости, которые не зависят ни от кого из
 * игроков: находка древнего корабля, землетрясение на колонии, пираты в казне, воронка
 * искривления на пути флота. Что именно случится и с кем — решает
 * {@link GalacticEventService}, фаза только называет момент.
 * <p>
 * Идёт после знакомства (15) и до дипломатии ИИ (17) намеренно: событие меняет положение
 * дел — казну, колонию, отношения, — и соседи должны решать, зная о случившемся, а не о
 * том, как было в начале хода.
 */
@Service
public class GalacticEventsPhase implements TurnPhase {

    private final GalacticEventService galacticEventService;

    public GalacticEventsPhase(GalacticEventService galacticEventService) {
        this.galacticEventService = galacticEventService;
    }

    @Override
    public Integer order() {
        return 16;
    }

    @Override
    public String name() {
        return "Галактические события";
    }

    @Override
    public void apply(TurnContext context) {
        galacticEventService.apply(context);
    }
}
