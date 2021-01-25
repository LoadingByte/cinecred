@echo off

echo Note: If 7zip is not installed, this script will fail.

REM Read the settings file
FOR /F "tokens=1,2 delims==" %%G IN (settings\general) DO (set %%G=%%H)

echo Downloading and extracting AdoptOpenJDK...
set jdk_zip=OpenJDK%JDK_MAJOR%U-jdk_x64_windows_hotspot_%JDK_MAJOR%.%JDK_MINOR%_%JDK_PATCH%.zip
set jdk_dir=jdk-%JDK_MAJOR%.%JDK_MINOR%+%JDK_PATCH%
wget.exe -q --show-progress --no-check-certificate "https://github.com/AdoptOpenJDK/openjdk%JDK_MAJOR%-binaries/releases/download/jdk-%JDK_MAJOR%.%JDK_MINOR%+%JDK_PATCH%/%jdk_zip%"
"C:\Program Files\7-Zip\7z.exe" x %jdk_zip%
del %jdk_zip%

echo Downloading and extracting Wix Toolset...
set wix_zip=wix311-binaries.zip
wget.exe -q --show-progress --no-check-certificate "https://github.com/wixtoolset/wix3/releases/download/wix3112rtm/%wix_zip%"
"C:\Program Files\7-Zip\7z.exe" x %wix_zip% -owix\
del %wix_zip%
set PATH=wix\;%PATH%

echo Running jlink...
%jdk_dir%\bin\jlink @settings\jlink

echo Running jpackage...
%jdk_dir%\bin\jpackage @settings\jpackage @settings\jpackage-windows

echo Cleaning up...
rmdir /S /Q runtime\
rmdir /S /Q wix\
rmdir /S /Q %jdk_dir%
