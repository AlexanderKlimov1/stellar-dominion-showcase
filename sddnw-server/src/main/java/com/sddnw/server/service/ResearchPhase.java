package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.domain.entity.DiplomacyRelationEntity;
import com.sddnw.server.domain.enums.DiplomacyStance;
import com.sddnw.server.repository.DiplomacyRelationRepository;
import com.sddnw.server.dto.AcquiredTechnologyDto;
import com.sddnw.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.Set;
import java.util.Random;

/**
 * Фаза исследований в конце хода — п. 9.
 * <p>
 * В MOO II порядок хода такой: население, доход очков, постройки, перевозки, завершение
 * исследований. Фаза делает два последних шага для исследований: начисляет игроку доход
 * очков и проверяет прорыв — поэтому доход текущего хода участвует в прорыве этого же хода.
 * <p>
 * <b>За ИИ цель выбирает сама фаза</b> — {@link ResearchService#autoChoose}: у него нет
 * экрана исследований, а без цели доход очков пропадает и империя навсегда остаётся с тем,
 * с чем начала. Выбор делается перед начислением очков, поэтому доход этого же хода уже
 * идёт в новую цель — иначе после каждого прорыва ИИ терял бы ход впустую.
 * <p>
 * Людям цель выбирают экраном: он сам открывается после прорыва (п. 9). Но если игрок
 * закончил ход, так и не выбрав, наука продолжает раздел последнего прорыва следующим
 * уровнем ({@link ResearchService#keepSection}, 30.09.2026) — прежде очки такого хода
 * пропадали целиком. Выбор при этом остаётся за игроком: сменить цель можно в любой ход.
 */
@Service
public class ResearchPhase implements TurnPhase {

    private final ResearchService researchService;
    private final AiEmpireService aiEmpireService;
    private final DiplomacyRelationRepository relationRepository;
    private final SpecialStarReward specialStarReward;
    /** Кто из людей ушёл из-за стола — его империю ведёт ИИ (backlog-promo, пункт 11). */
    private final PresenceService presence;

    public ResearchPhase(ResearchService researchService,
                         AiEmpireService aiEmpireService,
                         DiplomacyRelationRepository relationRepository,
                         SpecialStarReward specialStarReward,
                         PresenceService presence) {
        this.presence = presence;
        this.researchService = researchService;
        this.aiEmpireService = aiEmpireService;
        this.relationRepository = relationRepository;
        this.specialStarReward = specialStarReward;
    }

    @Override
    public Integer order() {
        return 10;
    }

    @Override
    public String name() {
        return "Исследования";
    }

    @Override
    public void apply(TurnContext context) {
        // Клад особой звезды — п. 4.2.1: наследие Стражей это тоже технологии, и место им
        // здесь. Фаза идёт после производства и хода ИИ, поэтому колония, основанная этим
        // же ходом, уже видна.
        specialStarReward.apply(context);

        // Воюющие империи спрашиваются одной выборкой на партию: список нужд ИИ зависит от
        // войны (десант в мирной галактике не нужен), а выборка «на игрока» внутри
        // посчитанного хода стоит дороже, чем кажется.
        Set<UUID> atWar = relationRepository
                .findAllByPlayerIdIn(context.players().stream().map(PlayerEntity::getId).toList())
                .stream()
                .filter(relation -> relation.getStance() == DiplomacyStance.WAR)
                .map(DiplomacyRelationEntity::getPlayerId)
                .collect(Collectors.toSet());

        for (PlayerEntity player : context.players()) {
            Random random = random(context.game(), player);
            // За ушедшего человека цель выбирает ИИ — пункт 11 backlog-promo.
            if (Boolean.TRUE.equals(presence.aiDriven(player))) {
                // Наука ИИ идёт сперва на то, чего империи не хватает для её же замысла
                // (расселение, застава, десант), и только потом на вкус правителя — п. 15.
                Set<String> known = context.colonyContext().technologiesByOwner()
                        .getOrDefault(player.getId(), Set.of());
                // В партии, где побеждает удержание Wardenhold, нужна ещё и дальность до
                // центра галактики (backlog-promo, пункт 3).
                researchService.autoChoose(player, random,
                        aiEmpireService.wantedTechnologies(known, atWar.contains(player.getId()),
                                context.game().getWardenholdVictory()));
            } else {
                // Человек не выбрал цель после прорыва — наука идёт дальше по прежнему
                // разделу, а не пропадает (п. 9, ResearchService.keepSection).
                researchService.keepSection(player, random);
            }
            // Колонии и их контекст берутся из хода: своя выборка на игрока стоила
            // дороже самой фазы — см. ResearchService.researchPerTurn.
            List<AcquiredTechnologyDto> gained = researchService.advance(
                    player, context.turn(), random, context.colonies(), context.colonyContext());
            for (AcquiredTechnologyDto technology : gained) {
                context.report().add(player.getId(), "RESEARCH",
                        new MessageKey("turn.research.done",
                                CatalogTexts.tech(technology.optionCode())));
            }
        }
    }

    /**
     * Прорыв случаен, но партия должна считаться одинаково при повторной загрузке того же
     * сохранения, поэтому генератор берёт зерно от партии, хода и слота игрока, а не от
     * общего источника случайности.
     */
    private Random random(GameEntity game, PlayerEntity player) {
        return new Random(game.getSeed() * 31L + game.getTurn() * 17L + player.getSlot());
    }
}
