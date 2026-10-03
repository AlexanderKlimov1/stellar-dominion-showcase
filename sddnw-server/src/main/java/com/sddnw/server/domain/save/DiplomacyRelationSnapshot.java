package com.sddnw.server.domain.save;

import com.sddnw.server.domain.enums.DiplomacyStance;
import com.sddnw.server.domain.enums.DiplomacyTreaty;

import java.util.List;

/**
 * Отношения двух империй в слепке партии — п. 15.
 * <p>
 * Стороны названы слотами, а не идентификаторами: при загрузке игроки заводятся заново
 * и получают новые идентификаторы, а слот у них тот же.
 */
public record DiplomacyRelationSnapshot(
        Integer playerSlot,
        Integer otherSlot,
        DiplomacyStance stance,
        Integer trust,
        List<DiplomacyTreaty> treaties,
        Integer metTurn,
        /** Знакомство действует — п. 15; {@code null} в старых слепках, и тогда «да». */
        Boolean inContact
) {
}
