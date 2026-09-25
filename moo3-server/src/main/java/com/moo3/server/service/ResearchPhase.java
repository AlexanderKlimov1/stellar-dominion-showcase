package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.enums.PlayerType;
import com.moo3.server.domain.entity.DiplomacyRelationEntity;
import com.moo3.server.domain.enums.DiplomacyStance;
import com.moo3.server.repository.DiplomacyRelationRepository;
import com.moo3.server.dto.AcquiredTechnologyDto;
import com.moo3.server.service.stub.TurnPhase;
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
 * Людям цель не выбирают: у них для этого есть экран, и подставлять им технологию за
 * спиной значило бы отнимать решение. Оставшийся без цели человек просто не исследует,
 * пока не выберет, — и экран сам открывается после прорыва, чтобы он не забыл (п. 9).
 */
@Service
public class ResearchPhase implements TurnPhase {

    private final ResearchService researchService;
    private final AiEmpireService aiEmpireService;
    private final DiplomacyRelationRepository relationRepository;
    private final SpecialStarReward specialStarReward;

    public ResearchPhase(ResearchService researchService,
                         AiEmpireService aiEmpireService,
                         DiplomacyRelationRepository relationRepository,
                         SpecialStarReward specialStarReward) {
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
            if (player.getPlayerType() == PlayerType.AI) {
                // Наука ИИ идёт сперва на то, чего империи не хватает для её же замысла
                // (расселение, застава, десант), и только потом на вкус правителя — п. 15.
                Set<String> known = context.colonyContext().technologiesByOwner()
                        .getOrDefault(player.getId(), Set.of());
                researchService.autoChoose(player, random,
                        aiEmpireService.wantedTechnologies(known, atWar.contains(player.getId())));
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
