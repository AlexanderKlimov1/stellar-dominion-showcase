package com.sddnw.server.service;

import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.PlayerType;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кто из людей за столом, а кто ушёл — backlog-promo, пункт 11.
 * <p>
 * <b>Живёт в памяти, а не в базе, и это нарочно.</b> Отметку присутствия ставит КАЖДОЕ
 * настоящее действие игрока, а строка игрока в базе держит версию: запись из каждого
 * запроса сталкивалась бы с записью самого действия и роняла бы его на проверке версии.
 * Потеря при перезапуске сервера безвредна: все считаются присутствующими, а правило
 * безделья ({@link TurnClockRules#IDLE}) через три минуты снова отметит ушедших — счёт
 * безделья начинается с первого вопроса о игроке (см. {@link #lastAction}). Точка расширения: несколько экземпляров
 * сервера потребуют общего хранилища — сейчас партию считает один.
 * <p>
 * <b>Настоящее действие — изменяющий запрос</b> (не GET): чтение карты, отчёта и
 * подписка на события идут сами, пока вкладка открыта, и присутствия не доказывают.
 */
@Service
public class PresenceService {

    private final Map<UUID, OffsetDateTime> lastAction = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> missed = new ConcurrentHashMap<>();
    private final Set<UUID> away = ConcurrentHashMap.newKeySet();

    /** Игрок что-то сделал: он за столом, и если уходил — вернулся. */
    public void act(UUID playerId) {
        lastAction.put(playerId, OffsetDateTime.now());
        missed.remove(playerId);
        away.remove(playerId);
    }

    /**
     * Когда игрок последний раз действовал. Не действовал на памяти сервера — отсчёт идёт
     * с первого вопроса: игрок, вошедший в партию до перезапуска сервера или через вход
     * без пропуска, иначе оказался бы «ушедшим» в тот же миг, как его спросили.
     */
    public OffsetDateTime lastAction(UUID playerId) {
        return lastAction.computeIfAbsent(playerId, id -> OffsetDateTime.now());
    }

    /** Игрок ушёл: сам, по безделью или по пропущенным срокам. */
    public void leave(UUID playerId) {
        away.add(playerId);
    }

    public Boolean isAway(UUID playerId) {
        return away.contains(playerId);
    }

    /** Ещё один пропущенный срок хода; возвращает, сколько их подряд. */
    public Integer miss(UUID playerId) {
        return missed.merge(playerId, 1, Integer::sum);
    }

    /**
     * Ведёт ли империю ИИ: соседа ИИ — всегда, человека — пока он ушёл. По этому признаку
     * решают фазы хозяйства ИИ, наука, встречи флотов и бой, а не по роду игрока: опекун
     * ушедшего — тот же ИИ, и второго свода его решений в игре нет.
     */
    public Boolean aiDriven(PlayerEntity player) {
        return player.getPlayerType() == PlayerType.AI
                || (player.getPlayerType() == PlayerType.HUMAN && isAway(player.getId()));
    }
}
