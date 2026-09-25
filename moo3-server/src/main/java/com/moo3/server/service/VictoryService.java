package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.enums.GameStatus;
import com.moo3.server.domain.enums.VictoryKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Чем кончается партия — п. 3.
 * <p>
 * В MOO II путей к победе три: покорить галактику, быть избранным Высшим советом и
 * разбить антаран у Антареса. Здесь их два — покорение и совет.
 * <p>
 * <b>Совет однажды убирали и вернули с оговорками.</b> Он даёт победу за две трети голосов
 * галактики, а голос весит как население: в партии из двух-трёх империй такое большинство
 * набиралось само собой, и партия обрывалась голосованием на двадцать пятом ходу, не
 * начавшись, — балансировке это било по рукам, а сквозному прогону мешало играть сценарии
 * на двоих. Теперь совет собирается не раньше пятидесятого хода, не чаще раза в двадцать
 * пять и только при трёх живых империях и более ({@link CouncilRules}), а при четырёх и
 * более две трети голосов значат, что победитель и так забрал галактику.
 * <p>
 * Антаран в игре нет вовсе — это <i>точка расширения</i>: появится Антарес, сюда встанет
 * ещё одна проверка, а всё остальное не изменится.
 * <p>
 * <b>Покорение.</b> Империя осталась в галактике одна: у остальных не осталось ни одной
 * колонии. Флот без колоний империей не считается — восстановиться ему негде.
 */
@Service
public class VictoryService {

    private static final Logger log = LoggerFactory.getLogger(VictoryService.class);

    private final CouncilService councilService;

    public VictoryService(CouncilService councilService) {
        this.councilService = councilService;
    }

    /**
     * Проверяет, не кончилась ли партия, и записывает победителя.
     * <p>
     * Вызывается последней фазой хода: к этому моменту сосчитано всё — захваты, колонии
     * и население, — и голосовать есть чем.
     */
    public void check(TurnContext context) {
        GameEntity game = context.game();
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            return;
        }

        Map<UUID, Integer> population = populationByPlayer(context);
        PlayerEntity conqueror = conqueror(context, population);
        if (conqueror != null) {
            finish(context, conqueror, VictoryKind.CONQUEST);
            return;
        }

        // Совет собирается ПОСЛЕ проверки покорения: когда колонии остались у одного,
        // голосовать не о чем, а объявлять победителя дважды незачем.
        PlayerEntity elected = councilService.hold(context, population);
        if (elected != null) {
            finish(context, elected, VictoryKind.COUNCIL);
        }
    }

    /** Население каждой империи: по нему считаются и одиночество, и голоса совета. */
    private Map<UUID, Integer> populationByPlayer(TurnContext context) {
        Map<UUID, Integer> population = new HashMap<>();
        for (PlanetEntity colony : context.colonies()) {
            population.merge(colony.getOwnerPlayerId(), colony.getPopulation(), Integer::sum);
        }
        return population;
    }

    /**
     * Победа покорением: колонии остались у одной империи.
     * <p>
     * Партия на одного не кончается никогда: считать покорителем единственного участника
     * значило бы объявлять победу на первом же ходу.
     */
    private PlayerEntity conqueror(TurnContext context, Map<UUID, Integer> population) {
        if (context.players().size() < 2 || population.size() != 1) {
            return null;
        }
        UUID lastStanding = population.keySet().iterator().next();
        return context.players().stream()
                .filter(player -> player.getId().equals(lastStanding))
                .findFirst()
                .orElse(null);
    }

    /** Записывает конец партии: дальше ходов в ней нет. */
    private void finish(TurnContext context, PlayerEntity winner, VictoryKind kind) {
        GameEntity game = context.game();
        game.setStatus(GameStatus.FINISHED);
        game.setWinnerPlayerId(winner.getId());
        game.setVictoryKind(kind);
        game.setFinishedAt(OffsetDateTime.now());

        for (PlayerEntity player : context.players()) {
            context.report().add(player.getId(), "VICTORY",
                    player.getId().equals(winner.getId())
                            ? new MessageKey("turn.victory.won", kind)
                            : new MessageKey("turn.victory.lost", winner.getName(), kind));
        }
        log.info("Партия {} окончена на ходу {}: победила империя {} ({})",
                game.getName(), context.turn(), winner.getName(), kind.getLabel());
    }
}
