package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.VictoryKind;
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
 * разбить антаран у Антареса. Здесь их четыре — покорение, совет, удержание Wardenhold
 * ({@link WardenholdRules}) и итог по могуществу на трёхсотом ходу
 * ({@link MightVictoryRules}). Все, кроме покорения, — признаки партии, которые окно новой
 * игры включает по умолчанию. Wardenhold стоит на месте антаранской победы — это цель у
 * особой звезды, которую видно с первого хода; итог по могуществу лечит затянутый финал.
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
    private final EmpireInfoService empireInfoService;

    public VictoryService(CouncilService councilService, EmpireInfoService empireInfoService) {
        this.councilService = councilService;
        this.empireInfoService = empireInfoService;
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
            // Покорение посреди открытого совета совет и закрывает: голосовать больше не о
            // чем, а спрашивать людей о выборах в оконченной партии было бы враньём сцены.
            game.setCouncilOpen(Boolean.FALSE);
            finish(context, conqueror, VictoryKind.CONQUEST);
            return;
        }

        // Удержание особой звезды — до совета: двадцать ходов держали на глазах у всех, и
        // голосование того же хода не должно отнимать заработанное.
        PlayerEntity warden = wardenhold(context);
        if (warden != null) {
            game.setCouncilOpen(Boolean.FALSE);
            finish(context, warden, VictoryKind.WARDENHOLD);
            return;
        }

        // Совет собирается ПОСЛЕ проверки покорения: когда колонии остались у одного,
        // голосовать не о чем, а объявлять победителя дважды незачем.
        PlayerEntity elected = councilService.hold(context, population);
        if (elected != null) {
            finish(context, elected, VictoryKind.COUNCIL);
            return;
        }

        // Итог по могуществу — последним: он нужен ровно тогда, когда ни один другой путь
        // к трёхсотому ходу не сработал (MightVictoryRules).
        PlayerEntity strongest = mightVerdict(context, population);
        if (strongest != null) {
            game.setCouncilOpen(Boolean.FALSE);
            finish(context, strongest, VictoryKind.MIGHT);
        }
    }

    /**
     * Итог по могуществу — п. 3, {@link MightVictoryRules}: на сроке победа отдаётся
     * сильнейшей живой империи. До срока — напоминания всем, за полсотни, десять и один ход:
     * срок, о котором не знали, — это обрыв, а не цель.
     */
    private PlayerEntity mightVerdict(TurnContext context, Map<UUID, Integer> population) {
        GameEntity game = context.game();
        if (!Boolean.TRUE.equals(game.getMightVictory())) {
            return null;
        }
        if (!MightVictoryRules.due(context.turn())) {
            if (Boolean.TRUE.equals(MightVictoryRules.remind(context.turn()))) {
                Integer left = MightVictoryRules.TURN_LIMIT - context.turn();
                for (PlayerEntity player : context.players()) {
                    context.report().add(player.getId(), "MIGHT_VERDICT",
                            new MessageKey("turn.might.soon", left));
                }
            }
            return null;
        }
        return MightVictoryRules.winner(empireInfoService.mightByPlayer(context),
                context.players(), population.keySet());
    }

    /**
     * Победа удержанием Wardenhold — п. 3, {@link WardenholdRules}.
     * <p>
     * Ведёт счёт удержания в самой партии (держатель и ход начала) и рассказывает о нём
     * ВСЕМ: двадцать ходов — это время ответить, а ответить можно только зная, что пора.
     * Держатель сменился или звезду потеряли — об этом тоже говорится, иначе игрок видел
     * бы, как счёт соседа просто пропал.
     *
     * @return победитель, если звезду продержали положенное число ходов
     */
    private PlayerEntity wardenhold(TurnContext context) {
        GameEntity game = context.game();
        if (!Boolean.TRUE.equals(game.getWardenholdVictory())) {
            return null;
        }
        StarSystemEntity special = context.systems().stream()
                .filter(system -> Boolean.TRUE.equals(system.getSpecial()))
                .findFirst()
                .orElse(null);
        if (special == null) {
            return null;
        }
        UUID holderId = WardenholdRules.holder(special.getPlanets());
        UUID previous = game.getWardenholdHolderPlayerId();
        if (holderId == null) {
            if (previous != null) {
                game.setWardenholdHolderPlayerId(null);
                game.setWardenholdSinceTurn(null);
                PlayerEntity lost = playerById(context, previous);
                for (PlayerEntity player : context.players()) {
                    context.report().add(player.getId(), "WARDENHOLD",
                            new MessageKey("turn.wardenhold.lost",
                                    lost == null ? "" : lost.getName(), special.getName()),
                            special.getId(), null);
                }
            }
            return null;
        }
        if (!holderId.equals(previous) || game.getWardenholdSinceTurn() == null) {
            game.setWardenholdHolderPlayerId(holderId);
            game.setWardenholdSinceTurn(context.turn());
        }
        PlayerEntity holder = playerById(context, holderId);
        if (holder == null) {
            return null;
        }
        Integer held = WardenholdRules.heldTurns(game.getWardenholdSinceTurn(), context.turn());
        Integer left = WardenholdRules.turnsLeft(game.getWardenholdSinceTurn(), context.turn());
        if (left == 0) {
            return holder;
        }
        if (Boolean.TRUE.equals(WardenholdRules.remind(held))) {
            for (PlayerEntity player : context.players()) {
                context.report().add(player.getId(), "WARDENHOLD",
                        player.getId().equals(holderId)
                                ? new MessageKey("turn.wardenhold.yours", special.getName(), left)
                                : new MessageKey("turn.wardenhold.theirs", holder.getName(), special.getName(), left),
                        special.getId(), null);
            }
        }
        return null;
    }

    private PlayerEntity playerById(TurnContext context, UUID playerId) {
        return context.players().stream()
                .filter(player -> player.getId().equals(playerId))
                .findFirst()
                .orElse(null);
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
