package com.sddnw.server.service;

import com.sddnw.server.service.stub.TurnPhase;
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
 * <p>
 * <b>И здесь же — предупреждение об угрозе</b> (backlog-promo, пункт 9): после посадки
 * в пути остаются ровно те флоты, что ещё придут, и о вражеском из них игрок узнаёт заранее
 * ({@link ThreatWarningService}). Своей фазы у предупреждения нет нарочно: ему нужно то же
 * мгновение, что и прибытию, — флоты уже сели, а встречи ещё не заведены.
 */
@Service
public class ArrivalPhase implements TurnPhase {

    private final FleetService fleetService;
    private final MonsterBattleService monsterBattles;
    private final ThreatWarningService threatWarnings;

    public ArrivalPhase(FleetService fleetService, MonsterBattleService monsterBattles,
                        ThreatWarningService threatWarnings) {
        this.fleetService = fleetService;
        this.monsterBattles = monsterBattles;
        this.threatWarnings = threatWarnings;
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
        threatWarnings.warn(context);
        // Разведчики с приказом «разведывать самим» уходят дальше тем же концом хода, в
        // котором встали (backlog-promo, пункт 24): иначе каждый второй ход они бы стояли.
        fleetService.continueExploring(context);
    }
}
