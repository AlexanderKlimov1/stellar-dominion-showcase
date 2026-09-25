package com.moo3.server.service;

import com.moo3.server.domain.RaceTrait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.moo3.server.domain.RaceTraitGroup;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.web.error.ConflictException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Правительство империи — п. 14.
 * <p>
 * Правительство выбирается в конструкторе расы наравне с прочими особенностями и живёт
 * в том же файле: диктатура ничего не меняет, демократия богатеет и лучше исследует,
 * объединение растёт и производит, феодализм теряет науку, но крепче держит десант и
 * шпионов. Поэтому у подсистемы нет ни своей таблицы, ни своих расчётов — её правила
 * это набор эффектов, которые расходятся по колониям, бою и шпионажу.
 * <p>
 * Здесь остаётся то, что относится к правительству как к отдельной вещи: как оно
 * называется у игрока и какие правительства вообще бывают.
 * <p>
 * <b>Строй растёт вместе с наукой</b> — п. 14: уровень «Advanced Government» приносит
 * развитый строй (Конфедерация, Империум, Федерация, Галактическое единство), и он
 * заменяет прежний прямо в наборе особенностей игрока. Очками развитый строй не
 * покупается: в конструкторе его нет, а какой именно достанется — решает не игрок, а
 * его нынешний строй, как и в оригинале.
 */
@Service
public class GovernmentService {

    private static final Logger log = LoggerFactory.getLogger(GovernmentService.class);

    private final RaceTraitCatalog catalog;

    public GovernmentService(RaceTraitCatalog catalog) {
        this.catalog = catalog;
    }

    /** Название правительства игрока; {@code null} — правительство не выбрано. */
    public String government(PlayerEntity player) {
        return catalog.government(player.getRaceTraitCodes());
    }

    /**
     * Меняет строй игрока, если изученное содержит его развитие — п. 14.
     * <p>
     * Замена идёт прямо в наборе особенностей: строй это обычная особенность своей
     * группы, и всё, что от неё зависит — колонии, шпионаж, флот, — начинает считаться
     * по-новому само, без единой правки в подсистемах.
     *
     * @return название нового строя; {@code null} — строй не изменился
     */
    public String upgradeAfterResearch(PlayerEntity player, Collection<String> acquired) {
        String government = catalog.governmentCode(player.getRaceTraitCodes());
        if (government == null) {
            return null;
        }
        RaceTraitCatalog.GovernmentUpgrade upgrade = catalog.governmentUpgrade(government);
        if (upgrade == null || !acquired.contains(upgrade.requiredTech())) {
            return null;
        }

        List<String> traits = new ArrayList<>(player.getRaceTraitCodes());
        traits.replaceAll(code -> government.equals(code) ? upgrade.code() : code);
        player.setRaceTraitCodes(traits);
        log.info("Игрок {} сменил строй на {}", player.getName(), upgrade.name());
        return upgrade.name();
    }

    /**
     * Не даёт изучать чужой строй — п. 14.
     * <p>
     * В MOO II уровень «Advanced Government» показывает империи только её собственное
     * развитие: диктатура растёт в Империум и никуда больше. Здесь дерево одно на всех
     * и показывает все четыре, поэтому выбор чужого строя отклоняется словами — иначе
     * игрок потратил бы уровень впустую и узнал бы об этом только по итогу.
     */
    public void requireOwnUpgrade(PlayerEntity player, String optionCode) {
        RaceTraitCatalog.GovernmentUpgrade chosen = catalog.governmentUpgrades().stream()
                .filter(upgrade -> upgrade.requiredTech().equals(optionCode))
                .findFirst()
                .orElse(null);
        if (chosen == null) {
            return;
        }

        String government = catalog.governmentCode(player.getRaceTraitCodes());
        if (government == null || !government.equals(chosen.replaces())) {
            RaceTraitCatalog.GovernmentUpgrade own = government == null
                    ? null
                    : catalog.governmentUpgrade(government);
            throw own == null
                    ? new ConflictException("government.notOnPath", chosen.name())
                    : new ConflictException("government.growsInto", own.name(), chosen.name());
        }
    }

    /** Все правительства конструктора — п. 14. */
    public List<RaceTrait> governments() {
        return catalog.groups().stream()
                .filter(group -> RaceTraitCatalog.GOVERNMENT_GROUP.equals(group.code()))
                .findFirst()
                .map(RaceTraitGroup::options)
                .orElse(List.of());
    }
}
