package com.moo3.server.config;

/**
 * Настройки входа в игру — п. 3.1.
 *
 * @param adminLogin      логин администратора: он заводится сам при первом запуске
 * @param adminFile       файл, куда кладётся логин и пароль администратора; пишется один
 *                        раз, при создании записи
 * @param sessionDays     сколько живёт пропуск сеанса
 * @param confirmHours    сколько живёт ссылка подтверждения из письма
 * @param mailFrom        обратный адрес письма
 * @param clientUrl       адрес клиента: из него собирается ссылка подтверждения, по
 *                        которой игрок возвращается в игру
 * @param mailOutboxDir   куда складывать письма, когда почтовый сервер не настроен —
 *                        без него регистрацию нельзя было бы ни проверить, ни пройти
 * @param loginAttempts   сколько неудачных попыток подряд выдерживает одна запись, прежде
 *                        чем вход в неё закроется на время (п. 3.1, защита от подбора)
 * @param loginIpAttempts то же для одного сетевого адреса: перебор идёт и по многим
 *                        записям сразу с одним частым паролём, и счётчик записи его не
 *                        видит вовсе
 * @param loginWindowMinutes окно, в котором неудачи считаются подряд идущими: более
 *                        старая неудача к перебору отношения не имеет
 * @param loginLockMinutes на сколько закрывается вход, когда порог перейдён
 * @param registrationsPerHour сколько записей можно завести с одного адреса за час:
 *                        каждая регистрация шлёт письмо на чужую почту, и без предела
 *                        сервер становится рассылкой чужими руками (п. 3.1)
 * @param registrationChallenge спрашивать ли задачку перед регистрацией («докажите, что
 *                        вы человек»). Домашней игре в локальной сети она ни к чему —
 *                        выключается настройкой (п. 3.1)
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "moo3.auth")
public record AuthProperties(
        String adminLogin,
        String adminFile,
        Integer sessionDays,
        Integer confirmHours,
        String mailFrom,
        String clientUrl,
        String mailOutboxDir,
        Integer loginAttempts,
        Integer loginIpAttempts,
        Integer loginWindowMinutes,
        Integer loginLockMinutes,
        Integer registrationsPerHour,
        Boolean registrationChallenge
) {
}
