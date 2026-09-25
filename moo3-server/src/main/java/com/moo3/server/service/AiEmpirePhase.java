package com.moo3.server.service;

import com.moo3.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

/**
 * Империи ИИ распоряжаются собой в конце хода — п. 15.
 * <p>
 * Идёт <b>сразу после производства</b> (порядок 8 против 7): производство только что
 * достроило здание, корабль или колониальную базу, и распорядиться готовым нужно тем же
 * ходом — иначе колония ИИ простояла бы ход без стройки, а построенный корабль ход
 * простоял бы дома.
 * <p>
 * До исследований (10) — потому что исследованиям всё равно, а после стройки список
 * доступного колонии уже верен. До прибытия (13) и встреч (14) — потому что приказ,
 * отданный здесь, должен успеть стать перелётом.
 * <p>
 * Решения — в {@link AiEmpireService}; фаза только называет момент.
 */
@Service
public class AiEmpirePhase implements TurnPhase {

    private final AiEmpireService aiEmpireService;

    public AiEmpirePhase(AiEmpireService aiEmpireService) {
        this.aiEmpireService = aiEmpireService;
    }

    @Override
    public Integer order() {
        return 8;
    }

    @Override
    public String name() {
        return "Империи ИИ";
    }

    @Override
    public void apply(TurnContext context) {
        aiEmpireService.play(context);
    }
}
