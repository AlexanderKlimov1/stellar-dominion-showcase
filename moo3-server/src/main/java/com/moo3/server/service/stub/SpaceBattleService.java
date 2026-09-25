package com.moo3.server.service.stub;

import com.moo3.server.service.BattleRules;
import com.moo3.server.service.MessageKey;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Космический бой — <b>заглушка</b> (п. 8).
 * <p>
 * Настоящего боя MOO II здесь нет: там это отдельная тактическая сцена с ходами кораблей,
 * дальностью, щитами и отступлением. Пока флоты сходятся автоматически, и исход считается
 * арифметикой — этого достаточно, чтобы работала вся обвязка вокруг боя: встреча флотов,
 * очередь решений, потери и итоги хода.
 * <p>
 * <b>Правило заглушки.</b> Сила стороны — число кораблей, помноженное на расовую прибавку
 * к атаке (её приносит {@code FleetService.initiative}). Побеждает сильнейший: слабый флот
 * гибнет целиком, победитель теряет тем больше кораблей, чем ближе была сила противника.
 * При равной силе гибнут оба флота — сходить с этого поля некому.
 * <p>
 * <b>Взрывы считаются и здесь</b> — п. 8. На поле корабль, которому разбили двигатель,
 * взрывается и бьёт соседей; поля тут нет, зато есть потери, и то же правило выражено
 * ими: часть погибших уносит с собой корабль победителя
 * ({@link BattleRules#FAST_BLAST_PERCENT}). Без этого один и тот же бой рассказывал бы
 * две разные истории — смотря, открыл игрок сцену или нажал «авто».
 * <p>
 * <b>Точка расширения.</b> Когда появится тактическая сцена, она встанет вместо
 * {@link #resolve}: снаружи бой уже описан как «две стороны вошли — вышел исход и потери»,
 * и остальному коду замена не видна. Признак «авто» игрок выбирает при нападении: с ним
 * бой считается сразу, без него — откроется сцена (пока тоже заглушка).
 */
@Service
public class SpaceBattleService {

    private final BattleRules rules;

    public SpaceBattleService(BattleRules rules) {
        this.rules = rules;
    }

    /**
     * Исход боя.
     *
     * @param attackerLosses сколько кораблей потерял напавший
     * @param defenderLosses сколько кораблей потерял обороняющийся
     * @param summary        итог боя ключом с подстановками — п. 3.5: язык читателя внутри
     *                       хода неизвестен, а итог ложится в самую встречу и в отчёт
     * @param attackerWins   победил напавший; {@code null} — взаимное уничтожение. Нужно
     *                       затем, чтобы отчёт сказал каждой стороне своё: одно и то же
     *                       «флот X разбил флот Y» обеим читается чужими словами
     */
    public record BattleOutcome(Integer attackerLosses, Integer defenderLosses,
                                MessageKey summary, Boolean attackerWins) {
    }

    /**
     * Считает бой двух флотов.
     *
     * @param attackerName      империя нападающего — для строки итога
     * @param attackerShips     кораблей у нападающего
     * @param attackerPower     сила нападающего с расовой прибавкой
     * @param defenderName      империя обороняющегося
     * @param defenderShips     кораблей у обороняющегося
     * @param defenderPower     сила обороняющегося с расовой прибавкой
     * @param seed              зерно боя: случайность партии выводится из её зерна, иначе
     *                          парные прогоны балансировки сравнивали бы не расы, а удачу
     */
    public BattleOutcome resolve(String attackerName, Integer attackerShips, Integer attackerPower,
                                 String defenderName, Integer defenderShips, Integer defenderPower,
                                 Long seed) {
        if (attackerPower.equals(defenderPower)) {
            return new BattleOutcome(attackerShips, defenderShips,
                    new MessageKey("battle.outcome.mutual", attackerName, defenderName), null);
        }

        Boolean attackerWins = attackerPower > defenderPower;
        Integer winnerShips = attackerWins ? attackerShips : defenderShips;
        Integer winnerPower = attackerWins ? attackerPower : defenderPower;
        Integer loserPower = attackerWins ? defenderPower : attackerPower;
        String winnerName = attackerWins ? attackerName : defenderName;
        String loserName = attackerWins ? defenderName : attackerName;

        // Победитель теряет тем больше, чем ближе была сила противника: доля потерь
        // равна отношению сил. Ноль кораблей победитель не теряет — бой всё же выигран.
        int winnerLosses = Math.min(winnerShips - 1, winnerShips * loserPower / Math.max(1, winnerPower));
        winnerLosses = Math.max(0, winnerLosses);

        // Гибнущие взрываются и уносят с собой соседей — п. 8. Соседи у погибающего флота
        // это прежде всего те, кто его добивает, поэтому лишние потери достаются
        // победителю. Больше, чем у него есть кораблей минус один, взрывы не уносят:
        // победа остаётся победой.
        RandomGenerator random = new Random(seed == null ? 0L : seed);
        int loserShips = attackerWins ? defenderShips : attackerShips;
        int blasts = rules.fastBlastLosses(random, loserShips);
        winnerLosses = Math.min(winnerShips - 1, winnerLosses + blasts);
        winnerLosses = Math.max(0, winnerLosses);

        MessageKey summary = new MessageKey("battle.outcome.beaten",
                winnerName, loserName, winnerLosses);
        return attackerWins
                ? new BattleOutcome(winnerLosses, defenderShips, summary, Boolean.TRUE)
                : new BattleOutcome(attackerShips, winnerLosses, summary, Boolean.FALSE);
    }
}
