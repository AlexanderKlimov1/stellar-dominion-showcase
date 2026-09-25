package com.moo3.server.service;

import com.moo3.server.domain.enums.GalaxySize;
import com.moo3.server.domain.enums.MineralRichness;
import com.moo3.server.domain.enums.PlanetClimate;
import com.moo3.server.domain.enums.PlanetSize;
import com.moo3.server.dto.GalaxySizeDto;
import com.moo3.server.dto.MineralRichnessDto;
import com.moo3.server.dto.PlanetClimateDto;
import com.moo3.server.dto.PlanetSizeDto;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Справочники игровых правил для экранов — п. 4.1 и п. 4.2.
 * <p>
 * <b>Берутся они из ПЕРЕЧИСЛЕНИЙ, а не из базы.</b> Раньше рядом с каждым перечислением
 * лежала таблица-близнец ({@code ref_galaxy_size}, {@code ref_planet_size},
 * {@code ref_planet_climate}, {@code ref_mineral_richness}), наполняемая Liquibase, и те же
 * числа были записаны дважды. Стоило это дважды:
 * <ul>
 *   <li><b>справочник в двух местах разъезжается.</b> Размеры галактик правились в
 *       перечислении (числа MOO II, п. 4.2), а в таблице оставались прежние — и экран
 *       создания партии показывал одно, а галактика строилась по другому;</li>
 *   <li><b>игру нельзя поднять на пустой схеме.</b> Балансовый прогон в памяти (профиль
 *       {@code balance}) создаёт базу заново при каждом старте, и справочникам взяться
 *       неоткуда: сервер отвечал «Справочник рас пуст» на первую же партию.</li>
 * </ul>
 * Правило проекта про данные в файлах (CLAUDE.md) здесь исполняется его же средствами:
 * числовые правила игры живут таблицами в {@code domain/enums}, и это и есть их файл.
 */
@Service
public class ReferenceService {

    /** Варианты размера галактики для экрана создания игры; по умолчанию выбран Huge. */
    public List<GalaxySizeDto> galaxySizes() {
        return Arrays.stream(GalaxySize.values())
                .map(size -> new GalaxySizeDto(
                        size.name(),
                        size.getLabel(),
                        size.getWidthParsecs(),
                        size.getHeightParsecs(),
                        size.getStarCount(),
                        size.getStarCount() + 1,
                        size == GalaxySize.DEFAULT))
                .toList();
    }

    public List<PlanetSizeDto> planetSizes() {
        return Arrays.stream(PlanetSize.values())
                .map(size -> new PlanetSizeDto(
                        size.name(),
                        size.getLabel(),
                        size.getBasePopulation()))
                .toList();
    }

    public List<PlanetClimateDto> planetClimates() {
        return Arrays.stream(PlanetClimate.values())
                .map(climate -> new PlanetClimateDto(
                        climate.name(),
                        climate.getLabel(),
                        climate.getPopulationMultiplierPercent(),
                        climate.getColonizable(),
                        climate.getTerraformOrder(),
                        climate.next().map(Enum::name).orElse(null),
                        climate.getRequiredTechCode()))
                .toList();
    }

    public List<MineralRichnessDto> minerals() {
        return Arrays.stream(MineralRichness.values())
                .map(richness -> new MineralRichnessDto(
                        richness.name(),
                        richness.getLabel(),
                        richness.getProductionPerWorker()))
                .toList();
    }
}
