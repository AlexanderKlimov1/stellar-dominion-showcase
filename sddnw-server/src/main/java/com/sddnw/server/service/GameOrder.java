package com.sddnw.server.service;

import com.sddnw.server.domain.entity.FleetEncounterEntity;
import com.sddnw.server.domain.entity.FleetEntity;
import com.sddnw.server.domain.entity.FleetShipEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PopulationTransferEntity;
import com.sddnw.server.domain.entity.ShipDesignEntity;
import com.sddnw.server.domain.entity.SpyEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/**
 * Порядок, в котором ход читает свои строки, — этап 0 балансировки.
 * <p>
 * <b>Зачем он вообще нужен.</b> Решения хода полны выборов «первый подходящий» и
 * «наименьший из равных»: кого накормить последним куском еды, чью стройку выкупить при
 * равной цене, чьему агенту достанется задание. Значит порядок строк — часть правил игры,
 * и та же партия с тем же зерном обязана его повторять: на этом держатся парные прогоны
 * балансировки, где сравниваются расы, а не удача.
 * <p>
 * <b>Почему порядок задаётся ЗДЕСЬ, а не в {@code ORDER BY}.</b> Дважды выяснялось, что
 * сортировка, выглядящая порядком, его не задаёт:
 * <ul>
 *   <li><b>по идентификатору строки</b> — он случайный UUID, который выдаёт Hibernate при
 *       записи, и во втором прогоне той же партии выпадает другим. Журнал, п. 3.84: партия
 *       расходилась сама с собой на 78-м ходу, потому что выкуп ИИ доставался то одной
 *       колонии, то другой;</li>
 *   <li><b>по строковой колонке в базе</b> — правила сравнения строк у СУБД РАЗНЫЕ, и та
 *       же партия на другой базе пошла бы иначе. Пока база одна, этого не видно; стоит
 *       завести второй источник данных (например, H2 в памяти для балансовых прогонов —
 *       его прикидывали 18.09.2026), и числа двух баз станут несравнимы, а причину искать
 *       будут в игре.</li>
 * </ul>
 * Поэтому порядок задаётся в Java и сравнением {@link String#compareTo} — по кодовым
 * единицам UTF-16. Оно одинаково на любой машине, в любой СУБД и в любой локали, потому
 * что не знает ни о какой локали вовсе. Выборки при этом отдают строки как им удобно, а
 * ход сортирует их сам: наборы тут в сотни строк, и сортировка их ничего не стоит.
 * <p>
 * <b>Ключи игровые, а не служебные.</b> Название планеты собрано из имени звезды и номера
 * орбиты, имя звезды взято из списка по зерну партии, место игрока и ход события заданы
 * самой партией — всё это выводится из зерна и потому повторяется.
 */
public final class GameOrder {

    private GameOrder() {
    }

    /**
     * Планеты — по названию: оно уникально (звезда плюс орбита) и выводится из зерна.
     * <p>
     * От этого порядка зависит подвоз еды: при равной нехватке последний кусок достаётся
     * той колонии, что идёт раньше ({@code PopulationCalculator.deliverFood}).
     */
    public static final Comparator<PlanetEntity> PLANETS =
            Comparator.comparing(PlanetEntity::getName);

    /** Звёзды — по имени; оно уникально в галактике и роздано по зерну партии. */
    public static final Comparator<StarSystemEntity> SYSTEMS =
            Comparator.comparing(StarSystemEntity::getName);

    /**
     * Проекты кораблей — по ячейке, потом по ходу создания, потом по названию.
     * <p>
     * Ячейка не уникальна: вытесненные проекты остаются в списке и делят её с новыми
     * ({@code ShipDesignService.replaceAutoDesign}), поэтому нужен второй ключ.
     */
    public static final Comparator<ShipDesignEntity> DESIGNS =
            Comparator.comparing(ShipDesignEntity::getSlot)
                    .thenComparing(ShipDesignEntity::getCreatedTurn)
                    .thenComparing(ShipDesignEntity::getName);

    /**
     * Флоты — по ходу прибытия, месту хозяина, звезде вылета и звезде назначения.
     * <p>
     * <b>От этого порядка зависит, кто выживет.</b> Фаза прибытия ведёт флоты по очереди, и
     * первому достаётся всё, что стоит в системе: сторожевое чудище (п. 11.1) и минное поле
     * Артемиды (п. 8). Два флота, пришедшие к одной звезде в один ход, — это не редкость, а
     * обычное дело: хорошая система притягивает соседей. Первый разбивает амёбу и гибнет
     * сам, второй входит в уже расчищенную систему и остаётся цел.
     * <p>
     * Выборка летящих флотов порядка не задавала вовсе, и база отдавала строки так, как они
     * лежат, — а лежат они по-разному, потому что ход их же и правит. Измерено 25.09.2026:
     * партия на зерне 6 (SMALL, шесть империй) расходилась сама с собой на 72-м ходу, и
     * расходилось ровно это — к звезде Scheat с амёбой подходили два флота, и амёбу
     * вскрывал то один, то другой. Дальше расхождение росло само: у одной империи флот был,
     * у другой нет.
     * <p>
     * Ключи выводятся из зерна партии: ход прибытия — число, место хозяина задано составом
     * партии, названия звёзд розданы по зерну. Число кораблей стоит последним и различает
     * два флота одного хозяина, летящих одним курсом; полной уникальности от него не
     * требуется — такие флоты в игре неотличимы.
     *
     * @param systemNames звезда → её название (у хода они уже загружены)
     * @param slots       игрок → его место в партии
     */
    public static Comparator<FleetEntity> fleets(Map<UUID, String> systemNames,
                                                 Map<UUID, Integer> slots) {
        return Comparator.comparing((FleetEntity fleet) ->
                        fleet.getArrivalTurn() == null ? 0 : fleet.getArrivalTurn())
                .thenComparing(fleet -> slots.getOrDefault(fleet.getOwnerPlayerId(), 0))
                .thenComparing(fleet -> systemNames.getOrDefault(fleet.getStarSystemId(), ""))
                .thenComparing(fleet -> systemNames.getOrDefault(fleet.getTargetSystemId(), ""))
                .thenComparing(FleetEntity::getShips);
    }

    /**
     * Встречи флотов — по ходу, затем по названию звезды и местам сторон.
     * <p>
     * От этого порядка зависит исход боёв: одна и та же империя ИИ разбирает свои встречи
     * подряд, и разбитый в первой встрече флот идёт во вторую уже обескровленным. Прежде
     * порядок задавался {@code ORDER BY turn, id} — и это ровно та ловушка, о которой
     * говорит заголовок этого файла: встречи одного хода делят номер хода, а
     * идентификатор строки — случайный UUID, свой у каждого прогона. Измерено 25.09.2026:
     * партия расходилась сама с собой на 112-м ходу, и первой расходилась сила флота.
     * <p>
     * Ключи все до одного выводятся из зерна партии: ход — номер, название звезды роздано
     * по зерну, место игрока задано составом партии. Пара звезда-плюс-места уникальна: флот
     * у империи в системе один, и встреча заводится на пару флотов.
     *
     * @param systemNames звезда → её название (у хода они уже загружены)
     * @param slots       игрок → его место в партии
     */
    public static Comparator<FleetEncounterEntity> encounters(Map<UUID, String> systemNames,
                                                             Map<UUID, Integer> slots) {
        return Comparator.comparing(FleetEncounterEntity::getTurn)
                .thenComparing(encounter -> systemNames.getOrDefault(
                        encounter.getStarSystemId(), ""))
                .thenComparing(encounter -> slots.getOrDefault(
                        encounter.getFirstPlayerId(), 0))
                .thenComparing(encounter -> slots.getOrDefault(
                        encounter.getSecondPlayerId(), 0));
    }

    /**
     * Рейсы с жителями — по ходу отправки, месту хозяина и названиям планет.
     * <p>
     * От этого порядка зависит, кто доедет: место на планете назначения общее, и рейс,
     * разобранный первым, занимает его первым, а второму остаётся ждать на борту
     * ({@code PopulationTransferService.arrive}). Выборка прибывающих рейсов порядка не
     * задавала вовсе.
     * <p>
     * Названия планет собраны из имени звезды и номера орбиты, а имя звезды роздано по
     * зерну партии, — значит ключ повторим.
     *
     * @param planetNames планета → её название (у хода они уже загружены)
     * @param slots       игрок → его место в партии
     */
    public static Comparator<PopulationTransferEntity> transfers(Map<UUID, String> planetNames,
                                                                Map<UUID, Integer> slots) {
        return Comparator.comparing(PopulationTransferEntity::getDepartedTurn)
                .thenComparing(transfer -> slots.getOrDefault(transfer.getOwnerPlayerId(), 0))
                .thenComparing(transfer -> planetNames.getOrDefault(transfer.getToPlanetId(), ""))
                .thenComparing(transfer -> planetNames.getOrDefault(transfer.getFromPlanetId(), ""))
                .thenComparing(PopulationTransferEntity::getPopulation);
    }

    /**
     * Строки состава флота — порядком самих проектов ({@link #DESIGNS}).
     * <p>
     * Нужен там, где из флота выбирают ОДИН корабль: слабейший на минном поле и в пасти
     * чудища ({@code FleetService.loseWeakest}), очередной под списание потерь боя
     * ({@code FleetService.applyLosses}). Равные по выбранной мерке строки там были и
     * остаются, а разрешался спор {@code thenComparing(FleetShipEntity::getId)} — то есть
     * случайным UUID строки. Измерено 25.09.2026: у одной и той же империи в слоте линкора
     * стояли ДВА проекта — заведённый на первом ходу и вытеснивший его на 49-м (п. 8,
     * «обновление идёт новой строкой»). Корпус у них один, значит прочность с бронёй
     * совпадает до числа, а пушки разные. Амёба съедала одного из двух, и в повторе той же
     * партии — другого: у флота выходила сила то 437, то 489, и партия расходилась сама с
     * собой на 111-м ходу.
     * <p>
     * Порядок проектов выводится из зерна партии (ячейка, ход создания, название), и
     * старший проект в споре проигрывает — он и есть более слабый корабль.
     *
     * @param designs проект по идентификатору, включая вытесненные: по ним летает
     *                построенное раньше
     */
    public static Comparator<FleetShipEntity> fleetShips(Map<UUID, ShipDesignEntity> designs) {
        return Comparator.comparing(row -> designs.get(row.getDesignId()),
                Comparator.nullsLast(DESIGNS));
    }

    /**
     * Агенты — по ходу найма, потом по заданию.
     * <p>
     * Задание сравнивается по ИМЕНИ ЗНАЧЕНИЯ перечисления, а не по тому, как оно легло в
     * базу: в базе это строка, и сравнивала бы её СУБД по своим правилам.
     */
    public static final Comparator<SpyEntity> SPIES =
            Comparator.comparing(SpyEntity::getCreatedTurn)
                    .thenComparing(spy -> spy.getMission().name());
}
