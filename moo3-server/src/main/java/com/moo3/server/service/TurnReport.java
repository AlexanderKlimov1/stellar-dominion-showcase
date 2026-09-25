package com.moo3.server.service;

import com.moo3.server.dto.TurnEventDto;
import com.moo3.server.dto.TurnReportDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Что случилось за ход — копится фазами, пока считается галактика (п. 11.1).
 * <p>
 * Отчёт ведётся по игрокам: одно и то же событие видно по-разному с двух сторон — у вора
 * «выкрал технологию», у обворованного «у вас выкрали». Поэтому событие кладётся тому,
 * кого оно касается, а не в общий список.
 * <p>
 * Отчёт нужен потому, что ход считается один раз на всех: игрок, закончивший первым,
 * узнаёт о пересчёте не из своего ответа, а из подписки, и без рассказа о случившемся
 * не понял бы, почему числа изменились.
 * <p>
 * Класс не потокобезопасен намеренно: ход одной партии считается под её блокировкой, в
 * один поток, — синхронизация тут была бы обманом, будто бывает иначе.
 */
public class TurnReport {

    private final Integer turn;
    private final Map<UUID, List<TurnEventDto>> events = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> systems = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> planets = new LinkedHashMap<>();

    public TurnReport(Integer turn) {
        this.turn = turn;
    }

    /** Событие империи: доход, изученная технология, пойманный агент. */
    public void add(UUID playerId, String code, MessageKey message) {
        add(playerId, code, message, null, null);
    }

    /**
     * Событие о планете. Планета и её система заодно помечаются изменившимися: клиенту
     * достаточно обновить их, а не всю галактику.
     * <p>
     * Текст события — <b>ключ с подстановками</b>, а не готовая строка (п. 3.5): ход
     * считается до того, как отчёт попросили показать, и языка получателя в этот миг нет.
     * Строку соберёт показ, на языке запроса.
     */
    public void add(UUID playerId, String code, MessageKey message,
                    UUID systemId, UUID planetId) {
        if (playerId == null || message == null) {
            return;
        }
        events.computeIfAbsent(playerId, id -> new ArrayList<>())
                .add(TurnEventDto.stored(code, message.key(), message.storedArgs(),
                        systemId, planetId));
        changed(playerId, systemId, planetId);
    }

    /** Событие, уже разобранное на ключ и подстановки: так его отдаёт очередь событий. */
    public void add(UUID playerId, String code, String key, List<String> args,
                    UUID systemId, UUID planetId) {
        if (playerId == null || key == null) {
            return;
        }
        events.computeIfAbsent(playerId, id -> new ArrayList<>())
                .add(TurnEventDto.stored(code, key, args, systemId, planetId));
        changed(playerId, systemId, planetId);
    }

    /**
     * Подстановки в строках: в базе они лежат рядом с ключом, а JSON типов не помнит.
     * <p>
     * Перечисление кладётся ключом своего ярлыка ({@code enum.<Тип>.<ИМЯ>}): показ переведёт
     * его вместе с самим сообщением, а положить сюда готовый ярлык значило бы решить за
     * читателя, на каком языке он читает.
     */
    public static List<String> stored(Object[] args) {
        List<String> stored = new ArrayList<>(args.length);
        for (Object arg : args) {
            /*
              Вложенного ключа С ПОДСТАНОВКАМИ здесь быть не может: в колонке от него
              осталась бы запись объекта, а не текст, — и отчёт показал бы «MessageKey[...]».
              Составное сообщение собирается ОДНИМ ключом со всеми подстановками; ключ БЕЗ
              подстановок передаётся обычной строкой — показ переведёт его сам.
             */
            if (arg instanceof MessageKey nested) {
                throw new IllegalArgumentException(
                        "Вложенный ключ в подстановке отчёта: " + nested.key());
            }
            stored.add(arg instanceof Enum<?> value
                    ? "enum." + value.getDeclaringClass().getSimpleName() + "." + value.name()
                    : String.valueOf(arg));
        }
        return stored;
    }

    /** Помечает изменившееся, когда рассказывать игроку нечего, а обновить нужно. */
    public void changed(UUID playerId, UUID systemId, UUID planetId) {
        if (playerId == null) {
            return;
        }
        if (systemId != null) {
            systems.computeIfAbsent(playerId, key -> new LinkedHashSet<>()).add(systemId);
        }
        if (planetId != null) {
            planets.computeIfAbsent(playerId, key -> new LinkedHashSet<>()).add(planetId);
        }
    }

    /** Отчёт для одного игрока: чужие события в него не попадают. */
    public TurnReportDto forPlayer(UUID playerId) {
        return new TurnReportDto(
                turn,
                List.copyOf(events.getOrDefault(playerId, List.of())),
                List.copyOf(systems.getOrDefault(playerId, Set.of())),
                List.copyOf(planets.getOrDefault(playerId, Set.of())));
    }

    public Integer turn() {
        return turn;
    }
}
