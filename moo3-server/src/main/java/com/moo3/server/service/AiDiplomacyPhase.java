package com.moo3.server.service;

import com.moo3.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

/**
 * Дипломатия империй ИИ в конце хода — п. 15.
 * <p>
 * Соседи приходят сами: объявляют войну, просят мира, предлагают договоры, требуют дань и
 * делают подарки. Что именно — решает характер правителя; само решение в
 * {@link AiDiplomacyService}, фаза только называет момент.
 * <p>
 * Идёт <b>последней</b> (порядок 17), после знакомства ({@link ContactPhase}, 15):
 * разговаривать можно лишь с тем, с кем уже знаком, и сосед, встреченный на этом же ходу,
 * должен успеть быть услышанным. Галактические события (16) к дипломатии отношения не
 * имеют, и порядок с ними безразличен.
 */
@Service
public class AiDiplomacyPhase implements TurnPhase {

    private final AiDiplomacyService aiDiplomacyService;

    public AiDiplomacyPhase(AiDiplomacyService aiDiplomacyService) {
        this.aiDiplomacyService = aiDiplomacyService;
    }

    @Override
    public Integer order() {
        return 17;
    }

    @Override
    public String name() {
        return "Дипломатия ИИ";
    }

    @Override
    public void apply(TurnContext context) {
        aiDiplomacyService.act(context);
    }
}
