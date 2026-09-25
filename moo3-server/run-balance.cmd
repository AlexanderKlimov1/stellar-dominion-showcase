@echo off
rem Сервер в режиме БАЛАНСОВОГО ПРОГОНА: база в памяти (H2), Postgres не нужен вовсе.
rem
rem Зачем: прогон играет сотни партий и каждую удаляет за собой — долговечность ему не
rem нужна ни на секунду, а обращения к базе стоят дорого (замер 18.09.2026: Postgres
rem съедал 1,9 ядра из двенадцати и держал потоки на ожидании, которое числом потоков не
rem закрывается). Правила игры при этом те же самые: те же сущности, репозитории и JPQL,
rem меняется только источник данных.
rem
rem ЧЕГО ЗДЕСЬ НЕТ: база создаётся пустой при старте и умирает вместе с процессом. Ни
rem сохранений, ни учётных записей прошлых запусков, ни журнала прогонов пульта. Для игры
rem и для истории прогонов поднимай обычный run.cmd.
rem
rem Учётные записи в этом режиме ТЕ ЖЕ, что и всегда: они живут в постоянном источнике
rem данных рядом с журналом прогонов, а не в памятной базе. Значит пароль администратора —
rem из обычного admin.txt, и отдельного файла не нужно. Сперва было наоборот: пустая база
rem заводила администратора заново и писала его в admin-balance.txt, а хозяин проекта
rem обнаружил это тем, что не смог войти своим паролем.

rem ЖУРНАЛ УХОДИТ В ФАЙЛ, а не в это окно. Сервер прогона поднимают фоном, окна никто не
rem видит, и всё, что он говорит, пропадало вместе с ним — включая причину, по которой
rem прогон оборвался. Файл при этом не растёт: в режиме `balance` журнал идёт на уровне
rem WARN (см. application-balance.yml), и за круг там набирается несколько килобайт вместо
rem прежних 2,4 гигабайта отладки, которую никто никогда не читал.
setlocal
set "JAVA_HOME=C:\openjdk-23.0.1"
set "TMPSOCK=%~dp0..\.tmp"
if not exist "%TMPSOCK%" mkdir "%TMPSOCK%"

"%JAVA_HOME%\bin\java" ^
  -Dfile.encoding=UTF-8 ^
  -Dspring.profiles.active=balance ^
  "-Dmoo3.game.star-names-file=%~dp0star-names.txt" ^
  "-Dmoo3.game.tech-file=%~dp0../resources/Technologies/tech.json" ^
  "-Dmoo3.game.buildings-file=%~dp0../resources/Buildings/buildings.json" ^
  "-Dmoo3.game.race-traits-file=%~dp0../resources/Races/race-traits.json" ^
  "-Dmoo3.game.ships-file=%~dp0../resources/Ships/ship-components.json" ^
  "-Dmoo3.game.leaders-file=%~dp0../resources/Leaders/leaders.json" ^
  "-Dmoo3.client.dist-dir=%~dp0../moo3-client/dist" ^
  "-Dmoo3.auth.admin-file=%~dp0../admin.txt" ^
  "-Dmoo3.orionometer.recorder=%~dp0../tools/moo2_record.py" ^
  "-Dmoo3.orionometer.brain=%~dp0../tools/moo3_learn.py" ^
  "-Dmoo3.orionometer.record-dir=%~dp0../tools/moo2-scenes/record" ^
  "-Dmoo3.auth.mail-outbox-dir=%~dp0../mail-outbox" ^
  "-Djdk.net.unixdomain.tmpdir=%TMPSOCK%" ^
  -jar "%~dp0target\moo3-server-0.1.0-SNAPSHOT.jar" %* >> "%~dp0server-balance.log" 2>&1
endlocal
