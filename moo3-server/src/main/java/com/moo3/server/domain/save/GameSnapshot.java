package com.moo3.server.domain.save;

import com.moo3.server.domain.enums.GalaxySize;

import java.util.List;

/**
 * Слепок партии на конец завершённого хода — то, что лежит в поле {@code state}
 * таблицы {@code game_save}.
 * <p>
 * Слепок не хранит идентификаторов: игроки, звёзды и планеты ссылаются друг на друга
 * номером слота и порядковым номером в списке. Поэтому загрузка создаёт новую партию
 * со своими идентификаторами и не зависит от того, жива ли ещё исходная игра.
 *
 * @param completedTurn номер завершённого хода: состояние снято на его конец
 * @param hostSlot      слот игрока-создателя партии
 */
public record GameSnapshot(
        String name,
        GalaxySize galaxySize,
        Integer widthParsecs,
        Integer heightParsecs,
        Integer starCount,
        Integer completedTurn,
        Integer totalPlayers,
        Integer maxHumanPlayers,
        Long seed,
        Integer hostSlot,
        List<PlayerSnapshot> players,
        List<StarSystemSnapshot> systems,
        /**
         * Идут ли в партии случайные галактические события — п. 11.1; {@code null} в
         * слепках, снятых до них, и тогда партия загружается с событиями, как в оригинале.
         */
        Boolean galacticEvents,
        /**
         * Собирается ли в партии Высший совет — п. 3; {@code null} в слепках, снятых до
         * его возвращения, и тогда читается «собирается», как в оригинале.
         */
        Boolean council,
        /**
         * Избрания совета уже не признали — п. 3; {@code null} в слепках, снятых до
         * отказа, и тогда читается «не отказывались». Отказ в партии ОДИН, и без этого
         * поля его можно было бы повторять сохранением и загрузкой.
         */
        Boolean councilRefused,
        /** Отношения империй — п. 15; {@code null} в слепках, снятых до дипломатии. */
        List<DiplomacyRelationSnapshot> relations,
        /** Флоты по системам — п. 8; {@code null} в слепках, снятых до флотов. */
        List<FleetSnapshot> fleets,
        /**
         * Проекты кораблей империй — п. 8; {@code null} в слепках, снятых до дизайна
         * кораблей. Сохраняются и вытесненные: по ним летают построенные корабли.
         */
        List<ShipDesignSnapshot> shipDesigns,
        /**
         * Рейсы с жителями — п. 4.1.1; {@code null} в слепках, снятых до перевозок.
         * Сохраняются потому, что в них летят живые жители: колония-отправитель их уже
         * лишилась, и потерять рейс значило бы потерять население.
         */
        List<PopulationTransferSnapshot> transfers
) {
}
