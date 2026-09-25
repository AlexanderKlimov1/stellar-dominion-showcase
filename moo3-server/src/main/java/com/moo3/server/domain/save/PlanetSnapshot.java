package com.moo3.server.domain.save;

import com.moo3.server.domain.PopulationJobs;
import com.moo3.server.domain.enums.MineralRichness;
import com.moo3.server.domain.enums.PlanetClimate;
import com.moo3.server.domain.enums.PlanetFind;
import com.moo3.server.domain.enums.PlanetSize;

import java.util.List;

/**
 * Планета в слепке партии: климат и население на конец хода — п. 4.1.
 *
 * @param ownerSlot слот игрока-владельца; {@code null} — планета ничья
 * @param population  жителей колонии целыми — так население писалось до появления роста
 * @param populationK население в тысячах жителей — п. 4.1.1; {@code null} в слепках,
 *                    снятых до появления роста, тогда берётся {@code population}
 * @param jobs          занятия жителей колонии — п. 4.1; {@code null} в слепках,
 *                      снятых до появления занятий
 * @param projectCode   что колония строит — п. 10
 * @param projectPoints вложено в стройку единиц производства
 * @param buildings     построенные здания — п. 10
 * @param colonyBaseReady колониальная база достроена и ждёт выбора планеты — п. 4.1;
 *                        {@code null} в слепках, снятых до появления баз
 * @param buildQueue    очередь стройки колонии — п. 10; {@code null} в слепках,
 *                      снятых до появления очереди
 * @param soldTurn      ход последней продажи постройки — п. 10: без него поднятая партия
 *                      давала бы продать вторую постройку тем же ходом; {@code null} в
 *                      слепках, снятых до появления продажи
 * @param find          находка на планете — п. 4.1; {@code null} в слепках, снятых до
 *                      появления находок, и тогда планета читается обыкновенной — какой
 *                      она в той партии и была
 * @param findClaimed   находка уже отдала выдаваемое один раз (технологии артефактов);
 *                      {@code null} в старых слепках. Без этого поля поднятое сохранение
 *                      приносило бы технологии заново — тем же путём, каким клад особой
 *                      звезды однажды открывался загрузкой
 */
public record PlanetSnapshot(
        Integer orbit,
        String name,
        PlanetSize planetSize,
        PlanetClimate climate,
        MineralRichness minerals,
        Integer maxPopulation,
        Integer ownerSlot,
        Integer population,
        Integer populationK,
        Boolean homeworld,
        PopulationJobs jobs,
        String projectCode,
        Integer projectPoints,
        List<PlanetBuildingSnapshot> buildings,
        Boolean colonyBaseReady,
        List<String> buildQueue,
        Integer soldTurn,
        PlanetFind find,
        Boolean findClaimed
) {
}
