package com.moo3.server.service;

import com.moo3.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

/**
 * Фаза прибытия флотов в конце хода — п. 8.
 * <p>
 * Идёт перед встречами (14): флот, дошедший до системы этим ходом, должен встретить в ней
 * чужой сразу, а не ходом позже. Разведка системы случается здесь же — п. 15: систему
 * открывает приход кораблей, а не отданный несколько ходов назад приказ.
 * <p>
 * <b>Здесь же чудище выходит на поле</b> (п. 11.1): флот человека, оказавшийся в
 * сторожевой системе, получает тактический бой. Заводится он ПОСЛЕ прибытия, а не внутри
 * него: драться чудище будет с флотом, который уже стоит в системе, — иначе бою нечего
 * было бы списывать потери. Империи ИИ решают тот же бой быстрым счётом, там же, где и
 * раньше ({@code FleetService.monster}).
 */
@Service
public class ArrivalPhase implements TurnPhase {

    private final FleetService fleetService;
    private final MonsterBattleService monsterBattles;

    public ArrivalPhase(FleetService fleetService, MonsterBattleService monsterBattles) {
        this.fleetService = fleetService;
        this.monsterBattles = monsterBattles;
    }

    @Override
    public Integer order() {
        return 13;
    }

    @Override
    public String name() {
        return "Прибытие флотов";
    }

    @Override
    public void apply(TurnContext context) {
        fleetService.arrive(context);
        monsterBattles.startPending(context);
    }
}
