package com.moo3.server.service;

import com.moo3.server.dto.RaceDesignDto;
import com.moo3.server.dto.RaceTraitDto;
import com.moo3.server.dto.SaveRaceTraitCostsRequest;
import com.moo3.server.web.error.ConflictException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Конструктор расы для клиента — п. 7.
 * <p>
 * Тонкая прослойка над {@link RaceTraitCatalog}: приводит группы особенностей к виду
 * для экрана создания расы. Сами правила и цены живут в файле конструктора.
 */
@Service
public class RaceDesignService {

    private final RaceTraitCatalog catalog;

    public RaceDesignService(RaceTraitCatalog catalog) {
        this.catalog = catalog;
    }

    public RaceDesignDto design() {
        List<RaceDesignDto.GroupDto> groups = catalog.groups().stream()
                .map(group -> new RaceDesignDto.GroupDto(
                        group.code(),
                        group.name(),
                        group.description(),
                        group.multiple(),
                        group.options().stream()
                                .map(trait -> new RaceTraitDto(
                                        trait.code(),
                                        trait.name(),
                                        trait.description(),
                                        trait.picks(),
                                        trait.excludes(),
                                        trait.effects().entrySet().stream()
                                                .map(effect -> new RaceTraitDto.Effect(
                                                        effect.getKey().name(), effect.getValue()))
                                                .toList()))
                                .toList()))
                .toList();
        List<RaceDesignDto.CombinationDto> combinations = catalog.combinations().stream()
                .map(one -> new RaceDesignDto.CombinationDto(
                        one.traits(),
                        one.traits().stream().map(code -> catalog.require(code).name()).toList(),
                        one.picks(),
                        one.note()))
                .toList();
        return new RaceDesignDto(catalog.picksBudget(), catalog.antiPicksBudget(),
                combinations, groups);
    }

    /**
     * Правка цен особенностей — п. 7. Меняет справочник на диске и отдаёт конструктор
     * заново: экран показывает то, что действительно легло в файл, а не то, что он послал.
     * <p>
     * Двух одинаковых кодов в правке быть не должно: это не «последний выигрывает», а
     * признак ошибки на клиенте — цену особенности задают один раз.
     */
    public RaceDesignDto updateCosts(SaveRaceTraitCostsRequest request) {
        Map<String, Integer> picksByCode = new LinkedHashMap<>();
        if (request.traits() != null) {
            for (SaveRaceTraitCostsRequest.TraitCost trait : request.traits()) {
                if (picksByCode.put(trait.code(), trait.picks()) != null) {
                    throw new ConflictException("race.traitTwiceInEdit", trait.code());
                }
            }
        }

        catalog.updatePicks(request.picks(), picksByCode);
        return design();
    }
}
