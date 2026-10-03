# Что заперто и почему

Эти файлы лежат здесь зашифрованными (`*.enc`). Без них ни сервер, ни клиент
не собираются. Ключ — у автора; открыть их можно `unlock.sh` или `unlock.cmd`.

| Файл | Без него нельзя |
|---|---|
| `sddnw-server/pom.xml` | без него не собрать сервер: Maven не знает ни зависимостей, ни сборки jar |
| `sddnw-server/src/main/resources/application.yml` | без него сервер не поднимется: нет ни базы, ни путей к справочникам |
| `sddnw-server/src/main/resources/application-balance.yml` | то же для режима балансовых прогонов |
| `sddnw-server/src/main/resources/db/changelog/db.changelog-master.xml` | без него Liquibase не построит схему базы |
| `sddnw-client/package.json` | без него клиент не собрать: нет ни зависимостей, ни команд |
| `sddnw-client/vite.config.ts` | без него нет сборки и dev-сервера |
| `sddnw-client/tsconfig.json` | без него не проходит проверка типов |
| `sddnw-client/tailwind.config.js` | без него нет ролей цвета и шкалы размеров |
| `sddnw-client/postcss.config.js` | без него не собирается стиль |

Шифр: AES-256-CBC, ключ выведен PBKDF2 (300 000 итераций), соль своя у каждого файла.

Это замок, а не сейф: описание сборки восстанавливается по самим исходникам.
Он поднимает цену запуска, но не делает его невозможным — и это сказано прямо.
