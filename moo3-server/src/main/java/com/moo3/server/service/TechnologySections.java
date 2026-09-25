package com.moo3.server.service;

import com.moo3.server.domain.Building;
import com.moo3.server.domain.ShipComponent;
import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.enums.ShipComponentSlot;
import com.moo3.server.domain.enums.TechnologySection;
import com.moo3.server.dto.ResearchCategoryDto;
import com.moo3.server.dto.ResearchLevelDto;
import com.moo3.server.dto.ResearchOptionDto;
import com.moo3.server.dto.ResearchTreeDto;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Раскладка изученного по четырём разделам окна Info — п. 11.1.
 * <p>
 * В MOO II список Tech Review делится на «General Achievements, Colony improvements,
 * Weapons и Ship Equipment». Дерево науки делится иначе — на восемь разделов, — поэтому
 * раздел окна выводится не из дерева, а из того, <b>что технология открывает</b>:
 * здание из справочника зданий — это улучшение колонии, пушка — оружие, корпус или
 * модуль — оснащение корабля, всё остальное — общее достижение.
 * <p>
 * Никакой новой таблицы соответствий заводить не пришлось: у каждого здания, корпуса и
 * компонента уже записана открывающая его технология ({@code required_tech}), и раздел
 * читается из тех же справочников-файлов. Новая пушка попадёт в «Оружие» сама, строкой
 * в {@code ship-components.json}, — как и всё в этом проекте, данными, а не веткой в коде.
 */
@Service
public class TechnologySections {

    private final BuildingCatalog buildingCatalog;
    private final ShipCatalog shipCatalog;
    private final Messages messages;

    public TechnologySections(BuildingCatalog buildingCatalog, ShipCatalog shipCatalog,
                              Messages messages) {
        this.buildingCatalog = buildingCatalog;
        this.shipCatalog = shipCatalog;
        this.messages = messages;
    }

    /**
     * То же дерево, но у каждой технологии проставлен раздел окна «Инфо».
     * <p>
     * Считается на лету и не запоминается: справочники правятся на ходу, без перезапуска
     * сервера, а дерево запрашивают один раз за подключение клиента.
     */
    public ResearchTreeDto withSections(ResearchTreeDto tree) {
        Map<String, TechnologySection> sections = sections();

        List<ResearchCategoryDto> categories = tree.categories().stream()
                .map(category -> new ResearchCategoryDto(
                        category.code(),
                        category.name(),
                        category.description(),
                        category.levels().stream()
                                .map(level -> withSections(level, sections))
                                .toList()))
                .toList();
        return new ResearchTreeDto(tree.version(), categories, tree.startingLevels());
    }

    private ResearchLevelDto withSections(ResearchLevelDto level,
                                          Map<String, TechnologySection> sections) {
        List<ResearchOptionDto> options = level.options().stream()
                .map(option -> {
                    TechnologySection section =
                            sections.getOrDefault(option.code(), TechnologySection.GENERAL);
                    return new ResearchOptionDto(option.code(), option.name(), option.description(),
                            option.recommended(), section.name(), messages.label(section));
                })
                .toList();
        return new ResearchLevelDto(level.order(), level.name(), level.cost(),
                level.cumulativeCost(), level.general(), options);
    }

    /**
     * Технология → её раздел. Собирается по справочникам: то, что технология открывает,
     * и решает, куда она попадёт.
     * <p>
     * Порядок важен: здания расставляются первыми, корабельное — следом. Одна технология
     * не открывает и здание, и пушку разом, но если такая появится, окно покажет её среди
     * колоний — там игрок и ищет постройку.
     */
    private Map<String, TechnologySection> sections() {
        Map<String, TechnologySection> sections = new HashMap<>();

        for (Building building : buildingCatalog.all()) {
            put(sections, building.requiredTechCode(), TechnologySection.COLONY);
        }
        for (ShipHull hull : shipCatalog.hulls()) {
            put(sections, hull.requiredTechCode(), TechnologySection.SHIP);
        }
        for (ShipComponent component : shipCatalog.components()) {
            put(sections, component.requiredTechCode(),
                    component.slot() == ShipComponentSlot.WEAPON
                            ? TechnologySection.WEAPON
                            : TechnologySection.SHIP);
        }
        return sections;
    }

    private void put(Map<String, TechnologySection> sections, String techCode, TechnologySection section) {
        // Технологии у справочной записи может не быть вовсе: стартовые корпуса и
        // компоненты доступны с первого хода и ничего не открывают.
        if (techCode != null && !sections.containsKey(techCode)) {
            sections.put(techCode, section);
        }
    }
}
