package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.BuildingEffects;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.enums.BuildingEffectType;
import com.moo3.server.domain.enums.PlanetClimate;
import com.moo3.server.domain.enums.PlanetGravity;
import com.moo3.server.domain.enums.PlanetSize;
import com.moo3.server.domain.enums.RaceEffectType;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.web.error.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.springframework.context.i18n.LocaleContextHolder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Стороны расы, которым в игре не хватало читателя, — п. 7.
 * <p>
 * Тяжесть миров, неприхотливость, командные очки и ассимиляция подданных: числа этих
 * правил дешевле проверить здесь, чем ловить их в партии, где они прячутся внутри
 * выработки колонии.
 */
class RaceAbilityRulesTest {

    private final PopulationCalculator population = new PopulationCalculator();
    private final CommandRules command = new CommandRules();
    private final AssimilationRules assimilation = new AssimilationRules();
    private final GroundCombatService ground =
            new GroundCombatService(null, null, null, null, null, null, null, assimilation, null, null, new OrbitalDefenceRules());

    private static RaceEffects race(Map<RaceEffectType, Integer> amounts) {
        return new RaceEffects(BuildingEffects.NONE, amounts);
    }

    @Test
    @DisplayName("Тяжесть мира считается от размера: крошечные лёгкие, огромные тяжёлые")
    void gravityFollowsSize() {
        assertThat(PlanetGravity.of(PlanetSize.TINY)).isEqualTo(PlanetGravity.LOW);
        assertThat(PlanetGravity.of(PlanetSize.SMALL)).isEqualTo(PlanetGravity.LOW);
        assertThat(PlanetGravity.of(PlanetSize.MEDIUM)).isEqualTo(PlanetGravity.NORMAL);
        assertThat(PlanetGravity.of(PlanetSize.LARGE)).isEqualTo(PlanetGravity.NORMAL);
        assertThat(PlanetGravity.of(PlanetSize.HUGE)).isEqualTo(PlanetGravity.HIGH);
    }

    @Test
    @DisplayName("Непривычная тяжесть роняет производство: четверть на лёгком, половина на тяжёлом")
    void gravityCostsProduction() {
        // Обычная раса: лёгкий мир −25%, тяжёлый −50%, свой обычный — без потерь.
        assertThat(PlanetGravity.LOW.productionPercent(Boolean.FALSE, Boolean.FALSE)).isEqualTo(-25);
        assertThat(PlanetGravity.NORMAL.productionPercent(Boolean.FALSE, Boolean.FALSE)).isZero();
        assertThat(PlanetGravity.HIGH.productionPercent(Boolean.FALSE, Boolean.FALSE)).isEqualTo(-50);

        // Раса малой тяжести: лёгкий мир родной, обычный уже нет, тяжёлый — вдвое хуже.
        assertThat(PlanetGravity.LOW.productionPercent(Boolean.TRUE, Boolean.FALSE)).isZero();
        assertThat(PlanetGravity.NORMAL.productionPercent(Boolean.TRUE, Boolean.FALSE)).isEqualTo(-25);
        assertThat(PlanetGravity.HIGH.productionPercent(Boolean.TRUE, Boolean.FALSE)).isEqualTo(-50);

        // Раса большой тяжести: тяжёлые и обычные миры ей нипочём, лёгкие — вдвое хуже.
        assertThat(PlanetGravity.HIGH.productionPercent(Boolean.FALSE, Boolean.TRUE)).isZero();
        assertThat(PlanetGravity.NORMAL.productionPercent(Boolean.FALSE, Boolean.TRUE)).isZero();
        assertThat(PlanetGravity.LOW.productionPercent(Boolean.FALSE, Boolean.TRUE)).isEqualTo(-50);
    }

    @Test
    @DisplayName("Неприхотливые считают любой мир земным, но выше райского не поднимаются")
    void tolerantTreatsEveryWorldAsTerran() {
        RaceEffects tolerant = race(Map.of(RaceEffectType.TOLERANT, 1));

        Integer toxic = population.maxPopulation(PlanetSize.LARGE, PlanetClimate.TOXIC);
        Integer toxicTolerant =
                population.maxPopulation(PlanetSize.LARGE, PlanetClimate.TOXIC, tolerant);
        Integer terran = population.maxPopulation(PlanetSize.LARGE, PlanetClimate.TERRAN);
        assertThat(toxicTolerant).isEqualTo(terran).isGreaterThan(toxic);

        // На райской планете неприхотливость не даёт ничего — как и в MOO II.
        assertThat(population.maxPopulation(PlanetSize.LARGE, PlanetClimate.GAIA, tolerant))
                .isEqualTo(population.maxPopulation(PlanetSize.LARGE, PlanetClimate.GAIA));
    }

    @Test
    @DisplayName("Подземные защищают колонию лучше, чем нападают")
    void subterraneanDefendsBetter() {
        RaceEffects diggers = race(Map.of(RaceEffectType.GROUND_DEFENCE_PERCENT, 10));

        assertThat(ground.strength(10, diggers, 0, Boolean.FALSE)).isEqualTo(100);
        assertThat(ground.strength(10, diggers, 0, Boolean.TRUE)).isEqualTo(110);
    }

    @Test
    @DisplayName("Командные очки: колония даёт два, военачальникам вчетверо, Империум в полтора раза")
    void commandPoints() {
        RaceEffects plain = RaceEffects.NONE;
        RaceEffects warlord = race(Map.of(RaceEffectType.WARLORD, 1));
        RaceEffects imperium = race(Map.of(RaceEffectType.COMMAND_PERCENT, 50));

        assertThat(command.capacity(5, plain)).isEqualTo(10);
        assertThat(command.capacity(5, warlord)).isEqualTo(20);
        assertThat(command.capacity(5, imperium)).isEqualTo(15);

        // Пока флот укладывается в запас, он бесплатен; за перебор платят по кредиту.
        assertThat(command.upkeep(10, 10)).isZero();
        assertThat(command.upkeep(14, 10)).isEqualTo(4);
    }

    @Test
    @DisplayName("Ассимиляция: объединению вдвое дольше, демократии и обаятельным вдвое быстрее")
    void assimilationSpeed() {
        assertThat(assimilation.turnsPerColonist(RaceEffects.NONE))
                .isEqualTo(AssimilationRules.BASE_TURNS_PER_COLONIST);
        // Объединение и отталкивающие — вдвое медленнее, демократия и обаяние — вдвое быстрее.
        assertThat(assimilation.turnsPerColonist(race(Map.of(RaceEffectType.ASSIMILATION_PERCENT, -50))))
                .isEqualTo(20);
        assertThat(assimilation.turnsPerColonist(race(Map.of(RaceEffectType.ASSIMILATION_PERCENT, 100))))
                .isEqualTo(5);
        // Складывается скорость, а не время: обаятельная демократия перевоспитывает втрое
        // быстрее обычной империи, а не мгновенно — мгновенно бывает только у феодальной жертвы.
        assertThat(assimilation.turnsPerColonist(race(Map.of(RaceEffectType.ASSIMILATION_PERCENT, 200))))
                .isEqualTo(3);
        // И не медленнее четверти обычной скорости: подданные становятся своими всегда.
        assertThat(assimilation.turnsPerColonist(race(Map.of(RaceEffectType.ASSIMILATION_PERCENT, -500))))
                .isEqualTo(40);
    }

    @Test
    @DisplayName("Подданных остаётся тем меньше, чем сильнее был удар")
    void subjectsAfterCapture() {
        // Вдвое более сильный захватчик оставляет половину жителей, десятикратный — десятую.
        assertThat(assimilation.subjects(10, 100, 200)).isEqualTo(5);
        assertThat(assimilation.subjects(10, 100, 1000)).isEqualTo(1);
        assertThat(assimilation.subjects(0, 100, 200)).isZero();
    }

    @Test
    @DisplayName("Строй растёт вместе с наукой и заменяет прежний — п. 14")
    void governmentGrowsWithResearch() {
        RaceTraitCatalog catalog = new RaceTraitCatalog(
                new GameProperties(8, 4, 1.5, "star-names.txt",
                        "../resources/Technologies/tech.json",
                        "../resources/Buildings/buildings.json",
                        "../resources/Races/race-traits.json",
                        "../resources/Ships/ship-components.json",
                        "../resources/Leaders/leaders.json"),
                new ObjectMapper());
        GovernmentService governments = new GovernmentService(catalog);

        PlayerEntity player = new PlayerEntity();
        player.setName("Тест");
        player.setRaceTraitCodes(List.of("gov-dictatorship", "warlord"));

        // Чужое развитие не изучить: диктатура растёт в Империум и никуда больше.
        assertThatThrownBy(() -> governments.requireOwnUpgrade(player, "federation"))
                .isInstanceOf(ConflictException.class);
        governments.requireOwnUpgrade(player, "imperium");

        // Своё — заменяет строй прямо в наборе особенностей, и всё считается по-новому.
        // Название развитого строя — на языке читателя (п. 3.5); язык здесь задан явно,
        // иначе проверка зависела бы от языка машины и порядка тестов.
        LocaleContextHolder.setLocale(Locale.of("ru"));
        assertThat(governments.upgradeAfterResearch(player, List.of("imperium")))
                .isEqualTo("Империум");
        LocaleContextHolder.resetLocaleContext();
        assertThat(player.getRaceTraitCodes()).containsExactly("gov-imperium", "warlord");
        assertThat(catalog.effects(player.getRaceTraitCodes()).commandPercent()).isEqualTo(50);
        assertThat(catalog.effects(player.getRaceTraitCodes()).espionageDefensePoints()).isEqualTo(2);
    }

    @Test
    @DisplayName("Подданные работают по правилам своей прежней расы, а свои — по правилам хозяина")
    void mixedColonyWorksByBothRaces() {
        BuildingEffects owner = new BuildingEffects(
                Map.of(BuildingEffectType.PRODUCTION_PER_WORKER, 2), 0);
        BuildingEffects aliens = new BuildingEffects(
                Map.of(BuildingEffectType.PRODUCTION_PER_WORKER, 0), 0);

        // Половина колонии — подданные: прибавка хозяина достаётся только своим.
        assertThat(owner.blend(aliens, 5, 5).amount(BuildingEffectType.PRODUCTION_PER_WORKER))
                .isEqualTo(1);
        // Подданных не осталось — колония работает целиком по правилам хозяина.
        assertThat(owner.blend(aliens, 10, 0).amount(BuildingEffectType.PRODUCTION_PER_WORKER))
                .isEqualTo(2);
    }
}
