package com.sddnw.server.service;

import com.sddnw.server.domain.ColonyProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Чего империя ИИ хочет от науки — п. 15.
 * <p>
 * Правило дешевле проверить здесь, чем ловить его в партии: прогон показывает последствие
 * (империя сидит на одной колонии триста ходов), а не причину.
 */
class AiNeedsTest {

    /**
     * Нужды считаются по переданным данным, поэтому службам тут неоткуда взяться.
     * <p>
     * Список {@code null} приходится править вместе с зависимостями службы — грабли,
     * записанные в правилах проекта: сборка ловит это позже всего, уже на тестах.
     */
    private final AiEmpireService ai = new AiEmpireService(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null);

    /** Основа кораблестроения — двигатель и топливо: без неё не строится ни один корабль. */
    private static final List<String> SHIPBUILDING =
            List.of(ShipDesignRules.DRIVE_TECH, ShipDesignRules.FUEL_TECH);

    /** Изученное вместе с основой кораблестроения: проверка смотрит на то, что идёт после неё. */
    private static Set<String> withShipbuilding(String... known) {
        Set<String> all = new java.util.HashSet<>(SHIPBUILDING);
        all.addAll(List.of(known));
        return all;
    }

    @Test
    @DisplayName("Основа кораблестроения — самая первая нужда: стартовых технологий нет")
    void shipbuildingComesFirst() {
        // С 01.10.2026 двигатель и топливо не выдаются на старте. Без них колониальный корабль
        // изучен, но не строится: прогон партии ИИ показал 24 колонии на восьмерых против 57.
        assertThat(ai.wantedTechnologies(Set.of(), Boolean.FALSE)).startsWith(
                ShipDesignRules.DRIVE_TECH, ShipDesignRules.FUEL_TECH, ColonyProject.COLONY_SHIP_TECH);
        assertThat(ai.wantedTechnologies(withShipbuilding(), Boolean.FALSE))
                .doesNotContainAnyElementsOf(SHIPBUILDING);
    }

    @Test
    @DisplayName("Расселение — первая нужда: без колониального корабля империя заперта")
    void expansionComesFirst() {
        List<String> wanted = ai.wantedTechnologies(withShipbuilding(), Boolean.FALSE);

        assertThat(wanted).startsWith(ColonyProject.COLONY_SHIP_TECH);
        assertThat(wanted).contains(ColonyProject.OUTPOST_SHIP_TECH);
        // Десант в мирной галактике не нужен: брать некого.
        assertThat(wanted).doesNotContain(ColonyProject.TRANSPORT_TECH);
    }

    @Test
    @DisplayName("Изученное из списка нужд уходит: империя не топчется на месте")
    void knownTechnologiesLeaveTheList() {
        List<String> wanted = ai.wantedTechnologies(
                withShipbuilding(ColonyProject.COLONY_SHIP_TECH), Boolean.FALSE);

        assertThat(wanted).doesNotContain(ColonyProject.COLONY_SHIP_TECH);
        assertThat(wanted).startsWith(ColonyProject.OUTPOST_SHIP_TECH);
    }

    @Test
    @DisplayName("Война добавляет к нуждам десант — п. 12")
    void warAddsTransports() {
        List<String> peace = ai.wantedTechnologies(
                withShipbuilding(ColonyProject.COLONY_SHIP_TECH, ColonyProject.OUTPOST_SHIP_TECH),
                Boolean.FALSE);
        List<String> war = ai.wantedTechnologies(
                withShipbuilding(ColonyProject.COLONY_SHIP_TECH, ColonyProject.OUTPOST_SHIP_TECH),
                Boolean.TRUE);

        // Война ДОБАВЛЯЕТ десант к тому, что империя хотела и без неё, — а не подменяет
        // собой весь список. Здесь стояло «в мире не хочется ничего», и это сломалось на
        // честной правке: хозяйственные технологии заведены нуждой (журнал, п. 3.67).
        assertThat(peace).doesNotContain(ColonyProject.TRANSPORT_TECH);
        assertThat(war).startsWith(ColonyProject.TRANSPORT_TECH);
        assertThat(war).containsAll(peace);
        assertThat(war).hasSize(peace.size() + 1);
    }

    @Test
    @DisplayName("Хозяйство — тоже нужда, но ПОСЛЕ расселения")
    void economyIsWantedAfterExpansion() {
        // Завод удваивает выработку рабочего, а колониальный корабль стоит 500 единиц:
        // строить его без завода значит строить втрое дольше. Но и откладывать расселение
        // ради завода нельзя — запертой на родной звезде империи не с кем ни воевать, ни
        // меняться, и прибор балансировки мерил бы застрявшую игру.
        List<String> wanted = ai.wantedTechnologies(withShipbuilding(), Boolean.FALSE);

        assertThat(wanted).startsWith(ColonyProject.COLONY_SHIP_TECH);
        assertThat(wanted).hasSizeGreaterThan(2);
        assertThat(wanted.indexOf(ColonyProject.OUTPOST_SHIP_TECH))
                .as("расселение раньше хозяйства")
                .isLessThan(wanted.size() - 1);

        // Изученное хозяйство из списка уходит — как и всё прочее нужное.
        String economy = wanted.getLast();
        assertThat(ai.wantedTechnologies(Set.of(economy), Boolean.FALSE))
                .doesNotContain(economy);
    }

    @Test
    @DisplayName("Империи, изучившей всё нужное, наука достаётся по вкусу правителя")
    void nothingWantedMeansObjectiveDecides() {
        // Набор «всего нужного» берётся У САМОГО ПРИБОРА, а не переписывается кодами:
        // список нужд растёт вместе с игрой, и вписанный сюда состав устаревает молча.
        Set<String> everything = Set.copyOf(ai.wantedTechnologies(Set.of(), Boolean.TRUE));

        assertThat(everything).isNotEmpty();
        assertThat(ai.wantedTechnologies(everything, Boolean.TRUE)).isEmpty();
    }
}
