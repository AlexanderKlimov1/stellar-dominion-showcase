package com.sddnw.server.service;

import com.sddnw.server.config.AuthProperties;
import org.springframework.stereotype.Service;

/**
 * Предел регистраций с одного адреса — п. 3.1.
 * <p>
 * <b>Зачем отдельно от общего предела частоты.</b> Регистрация стоит нам письма на чужой
 * адрес: заведя её тысячу раз, злоумышленник рассылает тысячу писем от нашего имени — и
 * платит за это репутацией нашего почтового ящика, а не своей. Общий предел частоты
 * ({@code RateLimitFilter}) считает запросы в секунду и такого не ловит вовсе: сорок
 * регистраций в минуту для него — тишина.
 * <p>
 * <b>Считаются только заведённые записи.</b> Отказы (почта занята, короткий пароль, нет
 * имени) письма не шлют и в счёт не идут: иначе один опечатавшийся игрок закрывал бы вход
 * себе и соседям по адресу, а сквозной прогон, который нарочно проверяет все отказы, не
 * проходил бы дважды подряд. Поток бессмысленных попыток и без того упирается в общий
 * предел частоты.
 * <p>
 * <b>Окно скользящее</b> ({@code sddnw.auth.registrations-per-hour} за час): считается не
 * «сколько было в этом часу», а «сколько за последний час», иначе на границе часов подряд
 * проходили бы две полные порции.
 * <p>
 * Счётчики живут в памяти, как и у {@link LoginThrottle}, и по той же причине: писать в
 * базу на каждую регистрацию ради предела, который переживает перезапуск сервера, здесь
 * незачем. Само окно и счёт — в {@link HourlyAddressLimit}, общем с пределом гостей.
 */
@Service
public class RegistrationThrottle extends HourlyAddressLimit {

    private final AuthProperties properties;

    public RegistrationThrottle(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected Integer limit() {
        return properties.registrationsPerHour();
    }

    @Override
    protected String refusalKey() {
        return "auth.throttle.registrations";
    }

    /** Запись заведена и письмо ушло — отмечаем, что адрес потратил одну попытку. */
    public void registered(String address) {
        spend(address);
    }
}
