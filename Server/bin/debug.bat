setlocal
echo on
set DIR=%~dp0
set PATH=%DIR%;%DIR%/../lib;%PATH%

java -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=9999 -cp %DIR%/../lib/goose-2.1.22.jar;%DIR%/../target/voiceInteractionServer-1.0-SNAPSHOT-jar-with-dependencies.jar com.github.unktomi.VoiceInteractionServer %*
