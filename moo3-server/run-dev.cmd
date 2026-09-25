@echo off
rem Second, INDEPENDENT server for working on the client while the main server on 8080
rem is busy with a balance run that must not be disturbed.
rem
rem     run-dev.cmd        run the built jar (target\...) on port 8081
rem     run-dev.cmd src    run from sources via mvn spring-boot:run - for server changes:
rem                        the main server keeps the jar open, so it cannot be rebuilt
rem                        while it runs, but target\classes is free
rem
rem Everything that could collide with the main server is separate:
rem   * port 8081;
rem   * database moo3_dev (create once: see README, section on the second dev server) -
rem     a second server on the SAME database would, at startup, mark the running balance
rem     run as failed (BalanceRunProgress.closeAbandoned) and race Liquibase migrations;
rem   * admin-dev.txt and mail-outbox-dev - a fresh database creates a fresh admin, and
rem     with the usual paths it would overwrite the real admin.txt;
rem   * its own AF_UNIX temp dir.
rem The Vite dev server is pointed at it with MOO3_API=http://localhost:8081
rem (launch config "moo3-client-dev").

setlocal
set "JAVA_HOME=C:\openjdk-23.0.1"
set "TMPSOCK=%~dp0..\.tmp-dev"
if not exist "%TMPSOCK%" mkdir "%TMPSOCK%"

rem TWO datasources, not one: besides spring.datasource the server has a separate
rem moo3.history.datasource (accounts and the balance-run journal, HistoryPersistenceConfig)
rem with its own url in application.yml. Overriding only spring.datasource left history on
rem the main database - and closeAbandoned marked the live oracle run FAILED on 21.09.2026.
set "DEV_PROPS=-Dfile.encoding=UTF-8 -Dserver.port=8081 -Dspring.datasource.url=jdbc:postgresql://localhost:5433/moo3_dev -Dmoo3.history.datasource.url=jdbc:postgresql://localhost:5433/moo3_dev -Dmoo3.game.star-names-file=%~dp0star-names.txt -Dmoo3.game.tech-file=%~dp0../resources/Technologies/tech.json -Dmoo3.game.buildings-file=%~dp0../resources/Buildings/buildings.json -Dmoo3.game.race-traits-file=%~dp0../resources/Races/race-traits.json -Dmoo3.game.ships-file=%~dp0../resources/Ships/ship-components.json -Dmoo3.game.leaders-file=%~dp0../resources/Leaders/leaders.json -Dmoo3.client.dist-dir=%~dp0../moo3-client/dist -Dmoo3.orionometer.recorder=%~dp0../tools/moo2_record.py -Dmoo3.orionometer.brain=%~dp0../tools/moo3_learn.py -Dmoo3.orionometer.record-dir=%~dp0../tools/moo2-scenes/record -Dmoo3.auth.admin-file=%~dp0../admin-dev.txt -Dmoo3.auth.mail-outbox-dir=%~dp0../mail-outbox-dev -Dmoo3.auth.client-url=http://localhost:5173 -Djdk.net.unixdomain.tmpdir=%TMPSOCK%"

if /i "%~1"=="src" goto :src

"%JAVA_HOME%\bin\java" %DEV_PROPS% -jar "%~dp0target\moo3-server-0.1.0-SNAPSHOT.jar"
goto :end

:src
cd /d "%~dp0"
rem Offline and with the home settings.xml (no corporate mirror): everything is already in
rem the local repository, but with the work settings.xml Maven refuses to use artifacts
rem "cached from a remote repository ID that is unavailable" whenever the VPN is down.
call mvn -B -q -o -s "%USERPROFILE%\.m2\settings-home.xml" spring-boot:run -Dspring-boot.run.jvmArguments="%DEV_PROPS%"

:end
endlocal
