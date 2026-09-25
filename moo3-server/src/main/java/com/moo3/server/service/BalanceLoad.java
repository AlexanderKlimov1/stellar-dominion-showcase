package com.moo3.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalTime;

/**
 * Сколько партий балансовый прогон считает разом — по времени суток и на ходу.
 * <p>
 * <b>Зачем.</b> Прогон занимает столько ядер, сколько ему дали, и на двенадцати потоках
 * двенадцатиядерная машина перестаёт годиться для работы: замер круга 8 держал 10,4 ядра
 * из 12 часами. Но ночью это ровно то, что нужно — считать быстрее. Поэтому число потоков
 * перестало быть постоянной сборки и стало настройкой: ночью больше, днём меньше.
 * <p>
 * <b>Почему на ходу, а не при старте.</b> Прежний предел читался один раз, из системного
 * свойства, при загрузке класса — поменять его можно было только перезапуском сервера, то
 * есть убив идущий прогон (а он идёт часами). Теперь предел спрашивается ПЕРЕД КАЖДОЙ
 * ПАРТИЕЙ, поэтому и ночное послабление, и правка руками действуют без перезапуска.
 * Огрубление одно и оно честное: партия, уже начатая, доигрывается — новая граница
 * вступает в силу со следующей, то есть в пределах нескольких минут.
 * <p>
 * <b>Как считается ночь.</b> Окно задано двумя часами и может переходить через полночь:
 * {@code 0..8} значит «с полуночи до восьми». Граница включает начало и не включает
 * конец — как у всякого промежутка времени.
 */
@Service
public class BalanceLoad {

    private static final Logger log = LoggerFactory.getLogger(BalanceLoad.class);

    /** Ночь начинается в полночь — решение хозяина проекта (21.09.2026). */
    private static final Integer DEFAULT_NIGHT_FROM = 0;

    /**
     * И кончается в восемь утра.
     * <p>
     * Конца у правила «после полуночи — двенадцать потоков» названо не было, а без него
     * оно истинно почти всегда: восемь утра взяты как разумное умолчание и меняются на
     * ходу, ради чего настройка и заводилась.
     */
    private static final Integer DEFAULT_NIGHT_UNTIL = 8;

    /** Ночью считаем в полную силу. */
    private static final Integer DEFAULT_NIGHT_WORKERS = 12;

    /**
     * Днём — половина от ночного, чтобы за машиной можно было работать.
     * <p>
     * Число это подбиралось живьём и дважды: двенадцать потоков держали 10,4 ядра из
     * двенадцати, и машина не годилась ни на что; четыре оказались уже с запасом, а шесть
     * — та середина, на которой хозяин проекта и остановился (22.09.2026). Шесть ядер из
     * двенадцати остаются работе.
     */
    private static final Integer DEFAULT_DAY_WORKERS = 6;

    private volatile Integer nightFrom = DEFAULT_NIGHT_FROM;
    private volatile Integer nightUntil = DEFAULT_NIGHT_UNTIL;
    private volatile Integer nightWorkers = DEFAULT_NIGHT_WORKERS;
    private volatile Integer dayWorkers = DEFAULT_DAY_WORKERS;

    /** Сколько партий считается прямо сейчас: по нему и решается, пускать ли следующую. */
    private Integer running = 0;

    /** Ночь ли сейчас — по системному времени машины, на которой идёт прогон. */
    public Boolean night() {
        return night(LocalTime.now().getHour());
    }

    /** То же по названному часу: так правило проверяется тестом без оглядки на часы. */
    public Boolean night(Integer hour) {
        if (nightFrom.equals(nightUntil)) {
            return Boolean.FALSE;
        }
        // Окно может переходить через полночь (например, 22..6), поэтому случая два.
        return nightFrom < nightUntil
                ? hour >= nightFrom && hour < nightUntil
                : hour >= nightFrom || hour < nightUntil;
    }

    /** Предел на сейчас: ночной или дневной. */
    public Integer workers() {
        return Boolean.TRUE.equals(night()) ? nightWorkers : dayWorkers;
    }

    /**
     * Сколько потоков держать в пуле: наибольший из пределов.
     * <p>
     * Пул делается по максимуму, а ограничивает не он, а {@link #take()}: пул нельзя
     * переразмерить посреди прогона, а счётчик — можно.
     */
    public Integer poolSize() {
        return Math.max(1, Math.max(nightWorkers, dayWorkers));
    }

    /**
     * Ждёт очереди и занимает место под партию — п. 2 этапа 2.
     * <p>
     * Предел спрашивается заново на каждом круге ожидания, поэтому смена времени суток
     * или правка руками подхватываются сами, без перезапуска и без остановки прогона.
     */
    public void take() {
        synchronized (this) {
            while (running >= workers()) {
                try {
                    // Просыпаемся и по своему сроку тоже: смену часа никто не «отпустит»,
                    // её надо заметить самому.
                    wait(30_000);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Прогон прерван в очереди на партию", stopped);
                }
            }
            running++;
        }
    }

    /** Освобождает место: партия доиграна. */
    public void release() {
        synchronized (this) {
            running = Math.max(0, running - 1);
            notifyAll();
        }
    }

    /** Сколько партий считается прямо сейчас — для пульта. */
    public Integer running() {
        synchronized (this) {
            return running;
        }
    }

    /**
     * Меняет настройку на ходу. Пустое значение оставляет прежнее.
     *
     * @return что получилось
     */
    public synchronized void update(Integer newNightFrom, Integer newNightUntil,
                                    Integer newNightWorkers, Integer newDayWorkers) {
        if (newNightFrom != null) {
            nightFrom = Math.floorMod(newNightFrom, 24);
        }
        if (newNightUntil != null) {
            nightUntil = Math.floorMod(newNightUntil, 24);
        }
        if (newNightWorkers != null) {
            nightWorkers = Math.max(1, newNightWorkers);
        }
        if (newDayWorkers != null) {
            dayWorkers = Math.max(1, newDayWorkers);
        }
        log.info("Нагрузка прогона: ночь {}..{} по {} потоков, день по {}",
                nightFrom, nightUntil, nightWorkers, dayWorkers);
        notifyAll();
    }

    public Integer nightFrom() {
        return nightFrom;
    }

    public Integer nightUntil() {
        return nightUntil;
    }

    public Integer nightWorkers() {
        return nightWorkers;
    }

    public Integer dayWorkers() {
        return dayWorkers;
    }
}
