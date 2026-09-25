package com.moo3.server.dto;

import com.moo3.server.domain.enums.GalaxySize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Создание игры — п. 3.1.
 * Размер галактики необязателен: по умолчанию Huge (п. 3).
 */
public record CreateGameRequest(
        @Size(max = 128)
        String name,

        GalaxySize galaxySize,

        @NotBlank
        @Size(max = 128)
        String playerName,

        /** Название родной звезды игрока; пусто — имя выберет генератор (п. 11.3). */
        @Size(max = 64)
        String homeStarName,

        String raceCode,

        /** Название расы, собранной игроком в конструкторе — п. 7; пусто — раса из справочника. */
        @Size(max = 128)
        String raceName,

        /** Коды выбранных особенностей расы — п. 7; пусто — раса без особенностей. */
        List<String> raceTraits,

        Long seed,

        /**
         * Идут ли в партии случайные галактические события — п. 11.1; пусто — идут.
         * <p>
         * В MOO II это переключатель окна новой игры, и по умолчанию события включены:
         * они часть игры, а не добавка к ней.
         */
        Boolean galacticEvents,

        /**
         * Партия без игрока-человека — п. 3.2: создатель смотрит, а его империю ведёт ИИ.
         * <p>
         * В MOO II такой партии нет, и в игре она нужна не для игры, а для проверки:
         * галактика из восьми империй ИИ доигрывается до победы сама, и на ней видно то,
         * чего не видно на одном ходу, — расселение, войны, захваты, конец партии. Пропуск
         * создателя при этом выдаётся обычный: им наблюдатель и объявляет конец хода.
         */
        Boolean observer,

        /**
         * Сколько всего империй в партии, считая создателя, — п. 3: в MOO II число
         * соперников выбирают в окне новой игры. Пусто — сколько задано настройкой
         * сервера ({@code moo3.game.total-players}).
         * <p>
         * Балансировке это нужно прямее, чем игроку: партия восьми империй на малой
         * галактике не кончается вовсе, а трёх на той же галактике — доигрывается, и
         * только на доигранных партиях проверяется, что суррогат мощи предсказывает исход
         * (`balance-metrics-works.txt`, этапы 0 и 1).
         */
        @Min(2)
        @Max(8)
        Integer totalPlayers,

        /**
         * Собирается ли в партии Высший совет — п. 3; пусто — собирается.
         * <p>
         * В MOO II это не переключатель, а часть игры, и по умолчанию совет здесь есть.
         * Выключают его ЗАМЕРЫ: балансовый прогон играет партию на заданное число ходов и
         * читает её летопись, а избранный правитель обрывает партию раньше срока —
         * измерение выходит короче назначенного и тем самым другим. Игроку этого поля не
         * видно: в окне новой игры его нет.
         */
        Boolean council
) {

    /** Сколько империй в партии; пусто — как задано настройкой сервера. */
    public Integer totalPlayersOr(Integer fallback) {
        return totalPlayers == null ? fallback : totalPlayers;
    }
    public GalaxySize galaxySizeOrDefault() {
        return galaxySize == null ? GalaxySize.DEFAULT : galaxySize;
    }

    /** Партия без людей: пустое поле значит «создатель играет сам». */
    public Boolean observerOrDefault() {
        return Boolean.TRUE.equals(observer);
    }

    /** События идут, пока их не выключили: пустое поле значит «как в оригинале». */
    public Boolean galacticEventsOrDefault() {
        return galacticEvents == null || Boolean.TRUE.equals(galacticEvents);
    }

    /** Совет собирается, пока его не выключили: пустое поле значит «как в оригинале». */
    public Boolean councilOrDefault() {
        return council == null || Boolean.TRUE.equals(council);
    }
}
