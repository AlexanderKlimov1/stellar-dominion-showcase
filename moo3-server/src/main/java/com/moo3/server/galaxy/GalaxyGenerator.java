package com.moo3.server.galaxy;

import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.StarColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.moo3.server.domain.enums.SpaceMonster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Генерация карты галактики — п. 3, п. 4.2, п. 4.2.1.
 * <p>
 * Галактика — прямоугольник {@code widthParsecs × heightParsecs}. Чтобы звёзды не сбивались
 * в кучи, поле режется на сетку клеток по числу звёзд, клетки перемешиваются, и в каждой
 * выбранной клетке звезда встаёт в случайную точку с отступом от границ клетки
 * (стратифицированная выборка). Отступ держит соседей на расстоянии не меньше
 * {@link GameProperties#minStarDistanceParsecs()} парсека, а на деле около трети клетки,
 * поэтому подписи на карте почти не налезают друг на друга.
 * <p>
 * Пустые клетки — их столько, на сколько сетка превысила число звёзд — оставляют карте
 * неравномерность, из-за которой расположение по-прежнему выглядит случайным.
 * <p>
 * Дополнительно к обычным звёздам ставится специальная звезда Wardenhold — она размещается
 * в центральной области галактики.
 */
@Component
public class GalaxyGenerator {

    private static final Logger log = LoggerFactory.getLogger(GalaxyGenerator.class);

    /** Доля стороны галактики, в пределах которой от центра размещается особая звезда. */
    private static final double SPECIAL_STAR_CENTRAL_FRACTION = 0.1;

    /** Минимальный отступ звезды от границ своей клетки — доля стороны клетки. */
    private static final double MIN_CELL_INSET = 0.15;

    /** Предельный отступ: при большем звёзды выстроились бы ровно по центрам клеток. */
    private static final double MAX_CELL_INSET = 0.4;

    /**
     * Доля высоты галактики, которую нижний ряд звёзд оставляет свободной под свои подписи:
     * подпись рисуется под звездой и не должна выходить за нижнюю границу поля (п. 11.3).
     */
    private static final double BOTTOM_LABEL_ROOM = 0.05;

    /** Запас попыток на одну звезду, чтобы не зациклиться на совпадающих координатах. */
    private static final Integer ATTEMPTS_PER_STAR = 16;

    /**
     * Шаг координатной сетки — десятая доля парсека (п. 4.2).
     * <p>
     * Целыми парсеками звёзды не расставить: в галактике Small 20×14 парсеков целых точек
     * всего 280 на три десятка звёзд, и они легли бы заметной решёткой. Десятая доля
     * парсека убирает решётку, а расстояния остаются теми же числами, какими меряется
     * дальность топлива.
     */
    private static final double COORDINATE_STEP = 0.1;

    private static final StarColor[] STAR_COLORS = StarColor.values();

    private final GameProperties gameProperties;
    private final StarNameCatalog starNameCatalog;
    private final PlanetGenerator planetGenerator;

    public GalaxyGenerator(GameProperties gameProperties,
                           StarNameCatalog starNameCatalog,
                           PlanetGenerator planetGenerator) {
        this.gameProperties = gameProperties;
        this.starNameCatalog = starNameCatalog;
        this.planetGenerator = planetGenerator;
    }

    /**
     * Строит звёздные системы галактики и привязывает их к игре.
     * Планеты создаются вместе с системами; сохранение — на вызывающей стороне.
     */
    public List<StarSystemEntity> generate(GameEntity game) {
        Random random = new Random(game.getSeed());
        StarNameCatalog.NamePicker names = starNameCatalog.picker(random);

        List<Point> positions = placeStars(game, random);
        List<StarSystemEntity> systems = new ArrayList<>(positions.size());

        // Особая звезда занимает первую позицию — она выбрана в центральной области галактики.
        for (int i = 0; i < positions.size(); i++) {
            Point point = positions.get(i);
            boolean orion = i == 0;

            StarSystemEntity system = new StarSystemEntity();
            system.setName(orion ? StarNameCatalog.SPECIAL_STAR : names.next());
            system.setXParsec(point.x());
            system.setYParsec(point.y());
            system.setStarColor(STAR_COLORS[random.nextInt(STAR_COLORS.length)]);
            system.setSpecial(orion);

            planetGenerator.populate(system, random);
            settleMonster(system, orion, random);
            // Связь ставим напрямую: коллекция game.starSystems ленивая, а добавление
            // в неё уже управляемой игры привело бы к merge-копиям вместо persist.
            system.setGame(game);
            systems.add(system);
        }

        log.debug("Сгенерирована галактика {}×{} парсеков: {} систем, {} планет",
                game.getWidthParsecs(), game.getHeightParsecs(), systems.size(),
                systems.stream().mapToInt(s -> s.getPlanets().size()).sum());
        return systems;
    }


    /**
     * Доля систем галактики, которые стережёт чудище, — реконструкция (п. 11.1).
     * <p>
     * MOO II числа не публиковала. Пятая часть: чудища должны попадаться на каждом
     * направлении расселения, иначе их не заметишь вовсе, — и при этом оставлять империи
     * достаточно чистых звёзд, чтобы партия не встала на первом же ходу.
     */
    private static final int MONSTER_SHARE_PERCENT = 20;

    /**
     * Ставит в систему чудище — п. 11.1.
     * <p>
     * <b>Особую звезду стережёт Страж, и всегда</b> — п. 4.2.1. Так в первоисточнике, и
     * оттуда же смысл: звезду не находят, её берут боем, а за боем стоит награда — клад
     * из трёх технологий ({@link com.moo3.server.service.SpecialStarReward}). Без стража
     * Wardenhold была бы просто звездой с хорошим именем.
     * <p>
     * <b>Родная звезда чудищу не достаётся:</b> империи расставляются позже
     * ({@link HomeworldAllocator}), и чудище в родной системе заперло бы игрока с первого
     * хода. Особая звезда в родные не попадает вовсе (там свой отбор), а выбранные системы
     * распределитель чистит сам.
     * <p>
     * Жребий берётся из того же {@code random}, что и вся галактика: партия обязана
     * повторяться до последнего числа.
     */
    private void settleMonster(StarSystemEntity system, boolean special, Random random) {
        if (special) {
            system.setMonster(SpaceMonster.GUARDIAN);
            system.setMonsterStrength(SpaceMonster.GUARDIAN.getStrength());
            return;
        }
        if (system.getPlanets().isEmpty()
                || random.nextInt(100) >= MONSTER_SHARE_PERCENT) {
            return;
        }
        List<SpaceMonster> kinds = SpaceMonster.all();
        int total = kinds.stream().mapToInt(SpaceMonster::getWeight).sum();
        int roll = random.nextInt(total);
        for (SpaceMonster kind : kinds) {
            roll -= kind.getWeight();
            if (roll < 0) {
                system.setMonster(kind);
                system.setMonsterStrength(kind.getStrength());
                return;
            }
        }
    }

    /**
     * Раскладывает координаты звёзд. Первая точка — особая звезда в центральной области,
     * остальные — по одной в случайно выбранных клетках сетки.
     */
    private List<Point> placeStars(GameEntity game, Random random) {
        int width = game.getWidthParsecs();
        int height = game.getHeightParsecs();
        int starCount = game.getStarCount() + 1;

        Grid grid = Grid.forGalaxy(width, height, starCount, gameProperties.minStarDistanceParsecs());
        List<Cell> cells = grid.cells();

        // Особая звезда идёт первой: его клетка выбрана в центре, остальные перемешаны.
        Cell orionCell = takeCentralCell(cells, grid, random);
        Collections.shuffle(cells, random);
        cells.add(0, orionCell);

        List<Point> placed = new ArrayList<>(starCount);
        Set<Point> occupied = new HashSet<>();
        for (Cell cell : cells) {
            if (placed.size() == starCount) {
                break;
            }
            Point point = pointInCell(cell, grid, width, height, random, occupied);
            if (point != null) {
                placed.add(point);
                occupied.add(point);
            }
        }

        if (placed.size() < starCount) {
            log.warn("Галактика {}×{} парсеков вместила {} звёзд из {}: сетка {}×{} клеток",
                    width, height, placed.size(), starCount, grid.columns(), grid.rows());
        }
        return placed;
    }

    /**
     * Клетка для особой звезды — из центральной области галактики (п. 4.2.1). Выбранная клетка
     * уходит из общего списка, чтобы вторая звезда в неё не попала.
     */
    private Cell takeCentralCell(List<Cell> cells, Grid grid, Random random) {
        double centerX = grid.width() / 2.0;
        double centerY = grid.height() / 2.0;
        double halfBoxX = Math.max(grid.cellWidth(), grid.width() * SPECIAL_STAR_CENTRAL_FRACTION);
        double halfBoxY = Math.max(grid.cellHeight(), grid.height() * SPECIAL_STAR_CENTRAL_FRACTION);

        List<Cell> central = cells.stream()
                .filter(cell -> Math.abs(grid.centerX(cell) - centerX) <= halfBoxX
                        && Math.abs(grid.centerY(cell) - centerY) <= halfBoxY)
                .toList();
        List<Cell> candidates = central.isEmpty() ? cells : central;

        Cell orionCell = candidates.get(random.nextInt(candidates.size()));
        cells.remove(orionCell);
        return orionCell;
    }

    /**
     * Случайная точка внутри клетки с отступом от её границ. Координаты округляются до
     * десятых долей парсека, поэтому соседние клетки изредка дают одну и ту же точку —
     * такую перевыбираем, а если не вышло, клетка остаётся пустой.
     */
    private Point pointInCell(Cell cell, Grid grid, Integer width, Integer height,
                              Random random, Set<Point> occupied) {
        for (int attempt = 0; attempt < ATTEMPTS_PER_STAR; attempt++) {
            double x = grid.minX(cell) + random.nextDouble() * grid.spanX();
            double y = grid.minY(cell) + random.nextDouble() * grid.spanY(cell);
            Point point = new Point(round(x, width), round(y, height));
            if (!occupied.contains(point)) {
                return point;
            }
        }
        return null;
    }

    /** Округляет до шага сетки и не выпускает звезду за край поля. */
    private Double round(double value, Integer bound) {
        double stepped = Math.round(value / COORDINATE_STEP) * COORDINATE_STEP;
        double clamped = Math.min(Math.max(stepped, 0), bound - COORDINATE_STEP);
        return Math.round(clamped * 10.0) / 10.0;
    }

    /**
     * Сетка клеток по числу звёзд: пропорции клетки повторяют пропорции галактики,
     * поэтому звёзды расходятся одинаково по обеим осям.
     *
     * @param insetX отступ от вертикальных границ клетки в парсеках
     * @param insetY отступ от горизонтальных границ клетки в парсеках
     */
    private record Grid(Integer width, Integer height, Integer columns, Integer rows,
                        Double cellWidth, Double cellHeight, Double insetX, Double insetY) {

        private static Grid forGalaxy(Integer width, Integer height, Integer starCount, Double minDistance) {
            int columns = Math.min(width, Math.max(1,
                    (int) Math.ceil(Math.sqrt((double) starCount * width / height))));
            int rows = Math.min(height, Math.max(1, (int) Math.ceil((double) starCount / columns)));

            double cellWidth = (double) width / columns;
            double cellHeight = (double) height / rows;
            return new Grid(width, height, columns, rows, cellWidth, cellHeight,
                    inset(cellWidth, minDistance), inset(cellHeight, minDistance));
        }

        /**
         * Отступ от границ клетки. Между звёздами соседних клеток остаётся не меньше
         * двух отступов, поэтому берём как минимум половину требуемой дистанции.
         */
        private static Double inset(Double cellSide, Double minDistance) {
            double inset = Math.max(cellSide * MIN_CELL_INSET, minDistance / 2.0);
            return Math.min(inset, cellSide * MAX_CELL_INSET);
        }

        private List<Cell> cells() {
            List<Cell> cells = new ArrayList<>(columns * rows);
            for (int column = 0; column < columns; column++) {
                for (int row = 0; row < rows; row++) {
                    cells.add(new Cell(column, row));
                }
            }
            return cells;
        }

        private Double minX(Cell cell) {
            return cell.column() * cellWidth + insetX;
        }

        private Double minY(Cell cell) {
            return cell.row() * cellHeight + insetY;
        }

        private Double spanX() {
            return cellWidth - insetX * 2;
        }

        /**
         * Высота полосы, в которой ставится звезда. У нижнего ряда полоса короче: под
         * подпись остаётся свободная кромка поля.
         */
        private Double spanY(Cell cell) {
            double span = cellHeight - insetY * 2;
            if (cell.row() < rows - 1) {
                return span;
            }
            double limited = height * (1 - BOTTOM_LABEL_ROOM) - minY(cell);
            return limited > 0 ? Math.min(span, limited) : span;
        }

        private Double centerX(Cell cell) {
            return (cell.column() + 0.5) * cellWidth;
        }

        private Double centerY(Cell cell) {
            return (cell.row() + 0.5) * cellHeight;
        }
    }

    /** Клетка сетки. */
    private record Cell(Integer column, Integer row) {
    }

    /** Координата в ГРЕ. */
    /** Точка карты в парсеках — с шагом в десятую долю (см. {@link #COORDINATE_STEP}). */
    public record Point(Double x, Double y) {
    }
}
