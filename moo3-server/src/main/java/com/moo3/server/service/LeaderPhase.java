package com.moo3.server.service;

import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Фаза лидеров в конце хода — п. 6: жалованье, опыт и новые предложения службы.
 * <p>
 * Идёт <b>после производства и до исследований</b>: жалованье платится из той же казны,
 * что пополнило производство, а прибавка «Исследователя» должна попасть в очки этого же
 * хода. Появление новых лидеров — здесь же: событие идёт в отчёт хода, иначе игрок узнал
 * бы о предложении, только заглянув на экран лидеров, и тридцать ходов ожидания истекли
 * бы сами собой.
 * <p>
 * Казна может уйти в минус: в MOO II жалованье платится и тогда, когда платить нечем, —
 * долг ложится на империю, а не отменяет службу.
 */
@Service
public class LeaderPhase implements TurnPhase {

    private final LeaderService leaderService;
    private final RaceService raceService;

    public LeaderPhase(LeaderService leaderService, RaceService raceService) {
        this.leaderService = leaderService;
        this.raceService = raceService;
    }

    @Override
    public Integer order() {
        return 9;
    }

    @Override
    public String name() {
        return "Лидеры";
    }

    @Override
    public void apply(TurnContext context) {
        Map<UUID, RaceEffects> races = raceService.effectsByPlayer(
                context.players().stream().map(PlayerEntity::getId).toList());
        Map<UUID, Integer> money = leaderService.advance(context.players(), context.turn(), races,
                context.game().getSeed());

        for (PlayerEntity player : context.players()) {
            Integer balance = money.getOrDefault(player.getId(), 0);
            if (balance == 0) {
                continue;
            }
            player.setCredits(player.getCredits() + balance);
            context.report().add(player.getId(), "LEADERS", balance > 0
                    ? new MessageKey("turn.leaders.income", balance)
                    : new MessageKey("turn.leaders.salary", -balance));
        }
    }
}
