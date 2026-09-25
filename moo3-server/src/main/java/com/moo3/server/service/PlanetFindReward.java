package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.PlanetFind;
import com.moo3.server.dto.AcquiredTechnologyDto;
import com.moo3.server.repository.PlanetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Что находка отдаёт ЗА САМУ РАЗВЕДКУ — п. 4.1.
 * <p>
 * Такая находка одна: наследие ушедшей цивилизации. «The first empire to visit the system
 * gets 1 or occasionally 2 free technologies» (StrategyWiki, «Which planets to colonize»),
 * и достаётся оно тому, кто долетел, а не тому, кто поселился: в этом и разница между
 * артефактами и золотом — золото ждёт колонию, артефакты вознаграждают разведку.
 * <p>
 * <b>Почему не фаза, как у клада особой звезды.</b> Клад смотрит на СОСТОЯНИЕ («у звезды
 * появился хозяин»), потому что колонию основывают тремя разными путями и правило,
 * написанное в одном из них, молчало бы в двух других. У разведки путь ОДИН: систему
 * открывает приход флота, и другого способа в игре нет ({@code ExplorationService} —
 * «разведывают только корабли»). Поэтому награда висит там же, где записывается сама
 * разведка, и молчать ей негде.
 * <p>
 * <b>Выдача технологий изнутри посчитанного хода тут допустима</b>: артефактов на
 * галактике единицы, и открывают их считанные разы за партию — это редкое событие, а не
 * выборка «на игрока» в каждой фазе (то же исключение, что у высадки десанта).
 */
@Service
public class PlanetFindReward {

    private static final Logger log = LoggerFactory.getLogger(PlanetFindReward.class);

    private final ResearchService researchService;
    private final PlanetRepository planetRepository;
    private final PlayerEventService playerEvents;

    public PlanetFindReward(ResearchService researchService,
                            PlanetRepository planetRepository,
                            PlayerEventService playerEvents) {
        this.researchService = researchService;
        this.planetRepository = planetRepository;
        this.playerEvents = playerEvents;
    }

    /**
     * Отдаёт награду за разведку системы, если в ней есть чему её отдать.
     * <p>
     * Зовётся, когда флот открыл систему впервые. Награда достаётся ОДИН раз на находку
     * ({@code planet.find_claimed}): вторая империя, добравшаяся до тех же артефактов,
     * застанет их уже разобранными — как и в оригинале, где это награда первому.
     */
    public void claim(GameEntity game, PlayerEntity player, StarSystemEntity system) {
        for (PlanetEntity planet : system.getPlanets()) {
            PlanetFind find = planet.getFind();
            if (find == null || !find.rewardsExplorer() || Boolean.TRUE.equals(planet.getFindClaimed())) {
                continue;
            }
            grant(game, player, system, planet, find);
        }
    }

    private void grant(GameEntity game, PlayerEntity player, StarSystemEntity system,
                       PlanetEntity planet, PlanetFind find) {
        // Жребий от зерна партии, хода и самой планеты: та же партия обязана считаться
        // так же, а две находки, открытые одним ходом, не должны делить один бросок.
        Random random = new Random(game.getSeed() * 97L + game.getTurn() * 31L
                + planet.getOrbit() + planet.getName().hashCode());
        int count = find.getFreeTechnologies()
                + (random.nextInt(100) < PlanetFind.SECOND_TECHNOLOGY_PERCENT ? 1 : 0);

        List<AcquiredTechnologyDto> gained = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // По одной: подарок берёт ближайший неизученный уровень случайного раздела —
            // так же, как клад особой звезды (п. 4.2.1).
            gained.addAll(researchService.grantGift(player, game.getTurn(), random, Boolean.FALSE));
        }

        // Отметка ставится и тогда, когда выдавать было нечего: империя, изучившая дерево
        // целиком, не должна оставлять находку «неразобранной» для следующего гостя.
        planet.setFindClaimed(Boolean.TRUE);
        planetRepository.save(planet);

        if (gained.isEmpty()) {
            log.info("Артефакты планеты {} достались игроку {}, но выдавать нечего: дерево изучено",
                    planet.getName(), player.getName());
            return;
        }
        playerEvents.record(game.getId(), player.getId(), game.getTurn(), "EXPLORATION",
                new MessageKey("turn.find.artifacts", system.getName(),
                        gained.stream()
                                .map(one -> CatalogTexts.tech(one.optionCode()))
                                .collect(Collectors.joining(", "))),
                system.getId(), null);
        log.info("Артефакты планеты {} достались игроку {}: {}", planet.getName(), player.getName(),
                gained.stream().map(AcquiredTechnologyDto::optionCode).toList());
    }
}
