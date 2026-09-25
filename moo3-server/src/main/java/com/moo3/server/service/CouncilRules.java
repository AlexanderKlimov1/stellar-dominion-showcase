package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.enums.GameStatus;
import com.moo3.server.domain.enums.VictoryKind;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Правила выборов Высшего совета — п. 3 (`docs/moo2/council.png`).
 * <p>
 * В MOO II совет галактики собирается время от времени и выбирает правителя: голосов у
 * империи столько, сколько у неё населения, а для избрания нужны <b>две трети</b> голосов
 * галактики. Кандидатов двое — сильнейшие по голосам; прочие голосуют за одного из них или
 * воздерживаются. Избранный побеждает.
 * <p>
 * <b>Совет однажды уже убирали из игры, и не зря.</b> Правило «две трети голосов» в партии
 * из двух-трёх империй выполняется само собой, и партия обрывалась голосованием на
 * двадцать пятом ходу, не начавшись; балансировке это било по рукам, а сквозному прогону
 * мешало играть сценарии на двоих. Поэтому совет вернулся с тремя оговорками, и каждая
 * лечит ровно ту беду:
 * <ul>
 *   <li><b>не раньше пятидесятого хода</b> ({@link #FIRST_TURN}) — к этому времени партия
 *       успевает сложиться;</li>
 *   <li><b>не меньше трёх живых империй</b> ({@link #MIN_EMPIRES}) — в поединке двоих
 *       голосование не выбор, а пересчёт населения;</li>
 *   <li><b>две трети от ВСЕХ голосов</b>: при четырёх и более империях такое большинство
 *       значит, что победитель и так забрал галактику, — совет лишь называет вслух то, что
 *       уже случилось.</li>
 * </ul>
 * Период между советами и первый ход — реконструкция: MOO II их не публиковала, известно
 * лишь, что совет собирается регулярно. Правятся они только здесь.
 * <p>
 * <b>Проигравший вправе не подчиниться</b> ({@link #canRefuse}), и тогда все, кто голосовал
 * за избранного, объявляют ему войну, а партия продолжается — правило оригинала. Отказ в
 * партии один: без этого следующий совет избрал бы того же, отказаться можно было бы снова
 * и снова, и победа советом перестала бы существовать вовсе.
 * <p>
 * Чего в совете нет: <b>голос человека подаётся тем же правилом, что и голос ИИ</b> — по
 * доверию к кандидатам. Ход считается на сервере целиком, спросить игрока посреди фазы
 * некого; чтобы спрашивать, нужен отдельный шаг перед концом хода.
 */
@Component
public class CouncilRules {

    /** Раньше этого хода совет не собирается вовсе — реконструкция. */
    public static final int FIRST_TURN = 50;

    /** Через столько ходов совет собирается снова — реконструкция. */
    public static final int PERIOD = 25;

    /** Меньше трёх живых империй — голосовать не о чем. */
    public static final int MIN_EMPIRES = 3;

    /** Доля голосов для избрания: две трети галактики, как в оригинале. */
    public static final int MAJORITY_NUMERATOR = 2;
    public static final int MAJORITY_DENOMINATOR = 3;

    /**
     * Доверие, ниже которого империя за кандидата не голосует.
     * <p>
     * Шкала доверия идёт от нуля до сотни, знакомство начинается с половины (п. 15).
     * Голосуют за того, к кому относятся ЛУЧШЕ, но только если к нему не хуже, чем
     * нейтрально: империя, которая терпеть не может обоих кандидатов, воздерживается —
     * «The Alkaris abstain» оригинала.
     */
    public static final int MIN_TRUST_TO_VOTE = 50;

    /** Собирается ли совет на этом ходу: не раньше пятидесятого и далее через двадцать пять. */
    /**
     * Вправе ли этот игрок не подчиниться избранию — п. 3.
     * <p>
     * Условий четыре, и каждое закрывает свой способ получить бесконечную партию: выборы
     * были и кого-то избрали, победа партии именно советская (покорение отказом не
     * отменить), отказа ещё не было, и отказывается не сам избранный.
     * <p>
     * Правило спрашивается из двух мест — из самого отказа и из ответа сцене, — и потому
     * живёт здесь, а не в службе: кнопка «не подчиниться» обязана гаснуть ровно там, где
     * сервер отказал бы. Причину отказа называет {@code CouncilService}: ей нужен не
     * ответ «нельзя», а то, какими словами это сказать.
     */
    public Boolean canRefuse(GameEntity game, UUID viewerPlayerId) {
        return game.getCouncilElectedPlayerId() != null
                && game.getStatus() == GameStatus.FINISHED
                && game.getVictoryKind() == VictoryKind.COUNCIL
                && !Boolean.TRUE.equals(game.getCouncilRefused())
                && viewerPlayerId != null
                && !viewerPlayerId.equals(game.getCouncilElectedPlayerId());
    }

    public Boolean convenes(Integer turn, Integer aliveEmpires) {
        return aliveEmpires >= MIN_EMPIRES
                && turn >= FIRST_TURN
                && (turn - FIRST_TURN) % PERIOD == 0;
    }

    /** Сколько голосов нужно кандидату: две трети от всех голосов галактики, вверх. */
    public Integer requiredVotes(Integer totalVotes) {
        return (totalVotes * MAJORITY_NUMERATOR + MAJORITY_DENOMINATOR - 1) / MAJORITY_DENOMINATOR;
    }

    /**
     * Двое сильнейших по голосам — кандидаты совета.
     * <p>
     * Порядок задан явно: при равенстве голосов берётся меньший идентификатор, иначе та же
     * партия с тем же зерном перестала бы повторяться — на этом здесь уже обжигались.
     */
    public List<UUID> candidates(Map<UUID, Integer> votesByPlayer) {
        return votesByPlayer.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(2)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * За кого голосует империя; {@code null} — воздерживается.
     * <p>
     * Кандидат всегда голосует за себя: в оригинале голоса кандидатов идут им самим.
     * Прочие выбирают того, к кому доверия больше, и только если этого доверия хватает
     * ({@link #MIN_TRUST_TO_VOTE}); при равном доверии к обоим империя воздерживается —
     * выбирать ей не из чего.
     */
    public UUID choice(UUID voter, UUID first, UUID second, Integer trustToFirst,
                       Integer trustToSecond) {
        if (voter.equals(first) || voter.equals(second)) {
            return voter;
        }
        if (trustToFirst > trustToSecond && trustToFirst >= MIN_TRUST_TO_VOTE) {
            return first;
        }
        if (trustToSecond > trustToFirst && trustToSecond >= MIN_TRUST_TO_VOTE) {
            return second;
        }
        return null;
    }
}
