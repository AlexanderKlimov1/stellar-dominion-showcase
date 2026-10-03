# -*- coding: utf-8 -*-
"""
Перевод базы на имена SDDNW (Stellar Dominion: Direction New World) — с данными, без
перекатки миграций.

    python tools\\sddnw_db_rename.py

Что делает, по шагам (каждый шаг сперва смотрит, не сделан ли он уже, поэтому скрипт
можно запускать повторно):

1. отказывает, пока к `moo3` или `moo3_dev` кто-то подключён: Postgres не переименовывает
   базу с живыми соединениями, а сервер, оставшийся на старом имени, потерял бы её посреди
   работы. Серверы 8080 и 8081 останавливают до запуска;
2. `moo3` → `sddnw`, `moo3_dev` → `sddnw_dev` (ALTER DATABASE … RENAME);
3. роль `moo3` → `sddnw` и пароль `sddnw` — тот, что теперь стоит в `application.yml`.
   Пароль задаётся заново намеренно: у пароля MD5 солью служит имя роли, и переименование
   его стирает (у SCRAM не стирает, но и стоять ему незачем — он был `moo3`);
4. журнал Liquibase в каждой из двух баз: `author` наборов `moo3` → `sddnw`, контрольные
   суммы обнуляются.

**Почему журнал правится, а не миграции перекатываются.** Опознаватель набора у Liquibase —
`id` + `author` + файл. Смени автора только в файлах, и все 128 наборов для Liquibase
станут новыми: он попытается накатить их поверх готовой схемы и упадёт на первой же
`createTable`. Перекатка с нуля это обходит, но теряет всё: учётные записи, партии,
историю балансовых прогонов и память проверенных связок. Правка журнала сохраняет данные,
а обнулённую сумму (`md5sum = NULL`) Liquibase при следующем старте пересчитывает сам,
ничего не накатывая, — тем же способом узакониваются и правленые комментарии наборов.

Суперпользователь берётся `postgres` без пароля: локальный вход у этого Postgres
доверенный (`pg_hba`, `trust` на 127.0.0.1). Где это не так — задать `PGPASSWORD` перед
запуском.
"""
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PSQL = os.environ.get('SDDNW_PSQL', r'C:\PostgreSQL15\bin\psql.exe')
PORT = '5433'
DATABASES = (('moo3', 'sddnw'), ('moo3_dev', 'sddnw_dev'))
OLD_ROLE, NEW_ROLE = 'moo3', 'sddnw'


def sql(query, database='postgres'):
    """Один запрос от суперпользователя; ответ — строки без шапки."""
    done = subprocess.run(
        [PSQL, '-h', 'localhost', '-p', PORT, '-U', 'postgres', '-d', database, '-w',
         '-v', 'ON_ERROR_STOP=1', '-At', '-c', query],
        capture_output=True, text=True, encoding='utf-8',
        env=dict(os.environ, PGCLIENTENCODING='UTF8'))
    if done.returncode != 0:
        raise SystemExit(f'Запрос не прошёл: {query}\n{done.stderr.strip()}')
    return [line for line in done.stdout.splitlines() if line]


def exists_database(name):
    return bool(sql(f"SELECT 1 FROM pg_database WHERE datname = '{name}'"))


def exists_role(name):
    return bool(sql(f"SELECT 1 FROM pg_roles WHERE rolname = '{name}'"))


def main():
    # 1. Никого на старых базах.
    olds = "', '".join(old for old, _ in DATABASES)
    busy = sql(f"SELECT datname, coalesce(nullif(application_name, ''), '?'), count(*) "
               f"FROM pg_stat_activity WHERE datname IN ('{olds}') GROUP BY 1, 2")
    if busy:
        print('К старым базам подключены — остановите их и запустите снова:')
        for row in busy:
            database, application, count = row.split('|')
            print(f'  {database}: {application} — соединений {count}')
        return 1

    # 2. Базы.
    for old, new in DATABASES:
        if exists_database(new):
            if exists_database(old):
                print(f'Есть и {old}, и {new} — какая из них настоящая, скрипт решать не будет.')
                return 1
            print(f'база {new}: уже на месте')
            continue
        if not exists_database(old):
            print(f'база {old}: нет и не было — пропущена')
            continue
        sql(f'ALTER DATABASE {old} RENAME TO {new}')
        print(f'база {old} → {new}')

    # 3. Роль.
    if exists_role(OLD_ROLE) and not exists_role(NEW_ROLE):
        sql(f'ALTER ROLE {OLD_ROLE} RENAME TO {NEW_ROLE}')
        print(f'роль {OLD_ROLE} → {NEW_ROLE}')
    elif exists_role(NEW_ROLE):
        print(f'роль {NEW_ROLE}: уже на месте')
    else:
        print(f'Роли нет ни под старым именем, ни под новым: заведите её (README, «База данных»).')
        return 1
    sql(f"ALTER ROLE {NEW_ROLE} PASSWORD '{NEW_ROLE}'")
    print(f'пароль роли {NEW_ROLE} задан')

    # 4. Журнал Liquibase.
    for _, new in DATABASES:
        if not exists_database(new):
            continue
        if not sql("SELECT 1 FROM information_schema.tables WHERE table_name = 'databasechangelog'",
                   database=new):
            print(f'{new}: журнала Liquibase нет — схему накатит первый старт сервера')
            continue
        changed = sql(f"WITH done AS (UPDATE databasechangelog SET author = '{NEW_ROLE}', md5sum = NULL "
                      f"WHERE author = '{OLD_ROLE}' RETURNING 1) SELECT count(*) FROM done",
                      database=new)[0]
        left = sql(f"SELECT count(*) FROM databasechangelog WHERE author = '{OLD_ROLE}'", database=new)[0]
        print(f'{new}: наборов переписано {changed}, со старым автором осталось {left}')

    print('Готово. Серверы поднимать уже сборкой с новыми именами.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
