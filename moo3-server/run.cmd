@echo off
rem Start the game server.
rem
rem jdk.net.unixdomain.tmpdir: the Windows user profile path on this machine contains
rem non-ASCII characters, so the JDK cannot create an AF_UNIX socket in the default
rem java.io.tmpdir. Selector.open() then fails and Tomcat does not start with
rem "Unable to establish loopback connection". An ASCII temp directory fixes it.
rem See README.md for the full explanation.

rem Accounts (spec 3.1): the admin credentials file and the mail outbox go to the
rem project root, not to whatever directory the server was started from.
rem
rem The built client (moo3-client\dist) is served by this server, so its path is passed
rem absolute as well. Publishing the game outside means publishing this port - the Vite
rem dev server has no authorization and hands out the project sources; see publish.bat.

setlocal
set "JAVA_HOME=C:\openjdk-23.0.1"
set "TMPSOCK=%~dp0..\.tmp"
if not exist "%TMPSOCK%" mkdir "%TMPSOCK%"

"%JAVA_HOME%\bin\java" ^
  -Dfile.encoding=UTF-8 ^
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
  -jar "%~dp0target\moo3-server-0.1.0-SNAPSHOT.jar" %*
endlocal
