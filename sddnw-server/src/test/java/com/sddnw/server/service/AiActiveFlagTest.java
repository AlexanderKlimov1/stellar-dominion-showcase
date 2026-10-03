package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Признак партии «ИИ действует» — трек техдолга, пункт 17.
 * <p>
 * Выключенный признак обязан глушить ОБЕ фазы соседей — хозяйство (стройка, флоты,
 * расселение) и дипломатию: сценарию, которому живой сосед мешает, мешает и его объявление
 * войны. А включённый — ничего не менять: партия игрока без признака в запросе играет как
 * прежде.
 */
class AiActiveFlagTest {

    private static TurnContext context(Boolean aiActive) {
        GameEntity game = new GameEntity();
        game.setTurn(1);
        game.setAiActive(aiActive);
        return new TurnContext(game, List.of(), List.of(), List.of(), null, new TurnReport(1));
    }

    @Test
    @DisplayName("Без действующего ИИ соседи не строят, не летают и не ведут переговоров")
    void idleAiDoesNothing() {
        AiEmpireService empires = Mockito.mock(AiEmpireService.class);
        AiDiplomacyService diplomacy = Mockito.mock(AiDiplomacyService.class);

        new AiEmpirePhase(empires).apply(context(false));
        new AiDiplomacyPhase(diplomacy).apply(context(false));

        verify(empires, never()).play(any());
        verify(diplomacy, never()).act(any());
    }

    @Test
    @DisplayName("Партия по умолчанию — с действующим ИИ, как и была")
    void activeByDefault() {
        AiEmpireService empires = Mockito.mock(AiEmpireService.class);
        AiDiplomacyService diplomacy = Mockito.mock(AiDiplomacyService.class);

        // Сущность партии заводится с признаком «действует»: старые партии и все, кто не
        // назвал признак в запросе, играют с соседями.
        new AiEmpirePhase(empires).apply(context(new GameEntity().getAiActive()));
        new AiDiplomacyPhase(diplomacy).apply(context(new GameEntity().getAiActive()));

        verify(empires, times(1)).play(any());
        verify(diplomacy, times(1)).act(any());
    }
}
