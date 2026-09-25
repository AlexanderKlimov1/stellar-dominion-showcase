package com.moo3.server.service;

import com.moo3.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

/**
 * Знакомство империй в конце хода — п. 15.
 * <p>
 * В MOO II контакт возникает не от встречи флотов, а от <b>соседства</b>: империи узнают
 * друг о друге, как только одна из них может послать корабли без дополнительных баков в
 * системы, занятые другой. Проверять это нужно каждый ход, потому что меняются обе
 * половины правила: колонии прибавляются (новая колония пододвигает границу к соседу), а
 * топливо улучшается технологиями и разом удлиняет все расстояния.
 * <p>
 * Идёт <b>после прибытия флотов и встреч</b> (порядок 15): к этому моменту ход закончил
 * всё, что могло сдвинуть границы империй, — колония заселена производством, флот
 * прилетел, бой отгремел и кого-то, возможно, не стало. Знакомиться логично с тем
 * положением, которое сложилось к концу хода, а не с промежуточным.
 * <p>
 * Само правило — в {@link DiplomacyService#contactByRange}: фаза только называет момент.
 */
@Service
public class ContactPhase implements TurnPhase {

    private final DiplomacyService diplomacyService;

    public ContactPhase(DiplomacyService diplomacyService) {
        this.diplomacyService = diplomacyService;
    }

    @Override
    public Integer order() {
        return 15;
    }

    @Override
    public String name() {
        return "Знакомство империй";
    }

    @Override
    public void apply(TurnContext context) {
        diplomacyService.contactByRange(context);
    }
}
