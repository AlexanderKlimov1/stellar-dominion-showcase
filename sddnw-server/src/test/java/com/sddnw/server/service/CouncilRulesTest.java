package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.domain.enums.VictoryKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила выборов Высшего совета — п. 3.
 * <p>
 * Проверяются оговорки, ради которых совет и вернули в игру: он не собирается рано, не
 * собирается часто и не собирается в поединке двоих. Ошибка в любой из них не падает и не
 * видна на экране — партия просто обрывается голосованием там, где не должна, и заметить
 * это можно будет лишь прогоном в сотни партий.
 */
class CouncilRulesTest {

    private final CouncilRules rules = new CouncilRules();

    private final UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID third = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    @DisplayName("Совет не собирается раньше пятидесятого хода и чаще раза в двадцать пять")
    void convenesLateAndRarely() {
        assertFalse(rules.convenes(25, 8), "двадцать пятый ход — та самая беда, из-за "
                + "которой совет когда-то убрали");
        assertFalse(rules.convenes(49, 8));
        assertTrue(rules.convenes(50, 8));
        assertFalse(rules.convenes(60, 8));
        assertTrue(rules.convenes(75, 8));
        assertTrue(rules.convenes(100, 8));
    }

    @Test
    @DisplayName("В поединке двоих совет не собирается вовсе")
    void duelHasNoCouncil() {
        assertFalse(rules.convenes(50, 2), "голосование двоих — это пересчёт населения, "
                + "а не выбор");
        assertTrue(rules.convenes(50, 3));
    }

    @Test
    @DisplayName("Для избрания нужны две трети голосов, и дробь округляется ВВЕРХ")
    void twoThirdsRoundedUp() {
        // Кадр оригинала: «59 Votes Total» — две трети это 39,33, и сороковой голос решает.
        assertEquals(40, rules.requiredVotes(59));
        assertEquals(2, rules.requiredVotes(3));
        assertEquals(60, rules.requiredVotes(90));
    }

    @Test
    @DisplayName("Кандидаты — двое сильнейших, а при равенстве решает порядок, а не случай")
    void candidatesAreTwoStrongest() {
        Map<UUID, Integer> votes = new LinkedHashMap<>();
        votes.put(third, 5);
        votes.put(first, 12);
        votes.put(second, 12);
        // Места нарочно идут ПРОТИВ порядка идентификаторов: ничья обязана решаться местом,
        // которое выведено из зерна партии, а не случайным UUID строки.
        UUID smallerId = first.compareTo(second) < 0 ? first : second;
        UUID largerId = smallerId.equals(first) ? second : first;
        Map<UUID, Integer> slots = Map.of(largerId, 1, smallerId, 2, third, 3);

        assertEquals(List.of(largerId, smallerId), rules.candidates(votes, slots),
                "при равных голосах берётся меньшее место: партия обязана повторяться "
                        + "до последнего числа");
    }

    @Test
    @DisplayName("Кандидат голосует за себя, прочие — за того, кому доверяют больше")
    void voteFollowsTrust() {
        assertEquals(first, rules.choice(first, first, second, 0, 0), "кандидат — за себя");
        assertEquals(second, rules.choice(third, first, second, 40, 80));
        assertEquals(first, rules.choice(third, first, second, 70, 60));
    }

    @Test
    @DisplayName("Не подчиниться вправе проигравший, и только один раз за партию")
    void refusalBelongsToTheLoser() {
        assertTrue(rules.canRefuse(finishedByCouncil(first), second), "проигравший вправе");
        assertFalse(rules.canRefuse(finishedByCouncil(first), first),
                "избранный не отказывается от собственного избрания");

        GameEntity refused = finishedByCouncil(first);
        refused.setCouncilRefused(Boolean.TRUE);
        assertFalse(rules.canRefuse(refused, second), "второго отказа не бывает: иначе "
                + "победа советом перестала бы существовать вовсе");

        GameEntity conquered = finishedByCouncil(first);
        conquered.setVictoryKind(VictoryKind.CONQUEST);
        assertFalse(rules.canRefuse(conquered, second), "покорение отказом не отменить");

        GameEntity running = finishedByCouncil(first);
        running.setStatus(GameStatus.IN_PROGRESS);
        assertFalse(rules.canRefuse(running, second), "идущая партия отказа не знает");

        GameEntity undecided = finishedByCouncil(first);
        undecided.setCouncilElectedPlayerId(null);
        assertFalse(rules.canRefuse(undecided, second), "совет никого не избрал");
    }

    /** Партия, оконченная избранием {@code elected}: с неё и начинается отказ. */
    private GameEntity finishedByCouncil(UUID elected) {
        GameEntity game = new GameEntity();
        game.setStatus(GameStatus.FINISHED);
        game.setVictoryKind(VictoryKind.COUNCIL);
        game.setWinnerPlayerId(elected);
        game.setCouncilElectedPlayerId(elected);
        game.setCouncilRefused(Boolean.FALSE);
        return game;
    }

    @Test
    @DisplayName("Не доверяя ни одному из кандидатов, империя воздерживается")
    void abstainsWhenTrustsNeither() {
        // «The Alkaris abstain (5 vote)» оригинала: доверия не хватает ни к тому, ни к другому.
        assertNull(rules.choice(third, first, second, 20, 30));
        // И при РАВНОМ доверии выбирать не из чего, даже когда оно высокое.
        assertNull(rules.choice(third, first, second, 80, 80));
    }

    @Test
    @DisplayName("Спрашивают только человека, который не кандидат")
    void asksOnlyHumanVoters() {
        // Человек не в кандидатах голосует сам — ради этого совет и идёт в два хода.
        assertTrue(rules.asks(PlayerType.HUMAN, third, first, second));
        // Кандидат голосует за себя без вопроса, как в оригинале.
        assertFalse(rules.asks(PlayerType.HUMAN, first, first, second));
        // ИИ не спрашивают: иначе партия без людей ждала бы ответа, которого не будет, и
        // замеры балансировки перестали бы считать совет в тот же ход.
        assertFalse(rules.asks(PlayerType.AI, third, first, second));
    }
}
