setlocal
echo on
set DIR=%~dp0
set PATH=%DIR%;%DIR%/../lib;%PATH%
cd %DIR%/..
java -cp %DIR%/../lib/goose-2.1.22.jar;%DIR%/../target/voiceInteractionServer-1.0-SNAPSHOT-jar-with-dependencies.jar com.github.unktomi.VoiceInteractionServer %*
