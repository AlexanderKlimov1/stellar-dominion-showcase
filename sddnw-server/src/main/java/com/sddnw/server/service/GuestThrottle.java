package com.sddnw.server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Предел гостевых записей с одного адреса — backlog-promo, пункт 1.
 * <p>
 * Гостевая запись стоит одного нажатия, а партия за ней — галактики в базе. Писем она не
 * шлёт, поэтому предел здесь мягче регистрационного ({@code sddnw.auth.guests-per-hour},
 * по умолчанию двадцать в час): его задача — не дать завалить базу партиями циклом, а не
 * беречь почтовый ящик. Живой человек столько гостевых входов за час не сделает, а
 * несколько игроков за одним адресом (дом, клуб, прокси) упрутся в него нескоро.
 */
@Service
public class GuestThrottle extends HourlyAddressLimit {

    private final Integer perHour;

    public GuestThrottle(@Value("${sddnw.auth.guests-per-hour:20}") Integer perHour) {
        this.perHour = perHour;
    }

    @Override
    protected Integer limit() {
        return perHour;
    }

    @Override
    protected String refusalKey() {
        return "auth.throttle.guests";
    }

    /** Гостевая запись заведена — адрес потратил одну попытку. */
    public void entered(String address) {
        spend(address);
    }
}
