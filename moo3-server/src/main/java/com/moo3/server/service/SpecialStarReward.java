package com.moo3.server.service;

import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.dto.AcquiredTechnologyDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Наследие Стражей: клад особой звезды — п. 4.2.1.
 * <p>
 * Wardenhold сторожит Страж, сильнейшее чудище галактики, и звезду не находят — её берут
 * боем. За бой полагается награда: империя, первой поселившаяся там, получает <b>три
 * технологии</b> даром. Так в первоисточнике («the technological goodies of Orion»,
 * StrategyWiki, Beating Orion's Guardian); само число — решение хозяина проекта, потому что
 * MOO II его не публиковала, и это <b>реконструкция</b>: правится оно только здесь.
 * <p>
 * <b>Почему это фаза хода, а не строка в месте заселения.</b> Колонию основывают ТРЕМЯ
 * путями — колониальным кораблём, готовой базой у игрока и её же копией у ИИ, — и правило,
 * написанное в одном из них, молчало бы в двух других: в этом проекте так уже четырежды
 * молчали счётчики. Фаза же смотрит на СОСТОЯНИЕ: у особой звезды появился хозяин, клад не
 * взят — значит, пора. Любой новый способ заселения подхватится сам.
 * <p>
 * <b>Клад достаётся один раз</b> ({@code star_system.special_claimed}): колонию отбивают и
 * заселяют заново, и без отметки каждый такой раз приносил бы три технологии — награда за
 * бой превратилась бы в источник дохода.
 */
@Service
public class SpecialStarReward {

    private static final Logger log = LoggerFactory.getLogger(SpecialStarReward.class);

    /**
     * Сколько технологий приносит клад — реконструкция, решение хозяина проекта (п. 4.2.1).
     * <p>
     * Три: меньше — и Страж не окупается (его берут поздно и дорогой ценой), больше — и
     * взявший уходит в отрыв, которого партия не переживает.
     */
    private static final int TECHNOLOGIES = 3;

    private final ResearchService researchService;

    public SpecialStarReward(ResearchService researchService) {
        this.researchService = researchService;
    }

    /**
     * Выдаёт клад, если особая звезда только что заселена.
     * <p>
     * Зовётся из фазы исследований — она идёт после производства и хода ИИ, поэтому колония,
     * основанная этим же ходом, уже видна.
     */
    public void apply(TurnContext context) {
        StarSystemEntity special = context.systems().stream()
                .filter(system -> Boolean.TRUE.equals(system.getSpecial()))
                .findFirst()
                .orElse(null);
        if (special == null || Boolean.TRUE.equals(special.getSpecialClaimed())) {
            return;
        }
        UUID ownerId = special.getPlanets().stream()
                .map(PlanetEntity::getOwnerPlayerId)
                .filter(owner -> owner != null)
                .findFirst()
                .orElse(null);
        if (ownerId == null) {
            return;
        }
        PlayerEntity owner = context.players().stream()
                .filter(player -> player.getId().equals(ownerId))
                .findFirst()
                .orElse(null);
        if (owner == null) {
            return;
        }

        // Жребий от зерна партии и хода: та же партия обязана считаться так же.
        Random random = new Random(context.game().getSeed() * 71L + context.turn() * 89L);
        List<AcquiredTechnologyDto> gained = new ArrayList<>();
        for (int i = 0; i < TECHNOLOGIES; i++) {
            // По одной: подарок берёт ближайший неизученный уровень случайного раздела, и
            // три вызова подряд дают три разных технологии, а не один уровень целиком.
            gained.addAll(researchService.grantGift(owner, context.turn(), random, Boolean.FALSE));
        }
        special.setSpecialClaimed(Boolean.TRUE);

        if (gained.isEmpty()) {
            // Изучать нечего — клад всё равно считается взятым: империя, изучившая дерево
            // целиком, не должна держать звезду «неразграбленной» до конца партии.
            log.info("Клад {} достался игроку {}, но выдавать нечего: дерево изучено",
                    special.getName(), owner.getName());
            return;
        }
        context.report().add(owner.getId(), "SPECIAL_STAR",
                new MessageKey("turn.specialStar.claimed", special.getName(),
                        gained.stream()
                                .map(one -> CatalogTexts.tech(one.optionCode()))
                                .collect(Collectors.joining(", "))),
                special.getId(), null);
        log.info("Клад {} достался игроку {}: {}", special.getName(), owner.getName(),
                gained.stream().map(AcquiredTechnologyDto::optionCode).toList());
    }
}
