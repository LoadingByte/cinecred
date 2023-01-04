@echo off

REM Read the settings file
FOR /F "tokens=1,2 delims==" %%G IN (settings\general) DO (set %%G=%%H)

mkdir work\
mkdir out\

echo Downloading and extracting AdoptOpenJDK...
set jdk_zip=OpenJDK%JDK_MAJOR%U-jdk_x64_windows_hotspot_%JDK_MAJOR%%JDK_MINOR%_%JDK_PATCH%.zip
set jdk_bin=work\jdk-%JDK_MAJOR%%JDK_MINOR%+%JDK_PATCH%\bin
powershell (new-object System.Net.WebClient).DownloadFile('https://github.com/adoptium/temurin%JDK_MAJOR%-binaries/releases/download/jdk-%JDK_MAJOR%%JDK_MINOR%+%JDK_PATCH%/%jdk_zip%', 'work\%jdk_zip%')
powershell Expand-Archive work\%jdk_zip% -DestinationPath work\
del work\%jdk_zip%

echo Downloading and extracting Wix Toolset...
set wix_zip=wix311-binaries.zip
powershell (new-object System.Net.WebClient).DownloadFile('https://github.com/wixtoolset/wix3/releases/download/wix3112rtm/%wix_zip%', 'work\%wix_zip%')
powershell Expand-Archive work\%wix_zip% -DestinationPath work\wix\
del work\%wix_zip%

echo Collecting minimized JRE...
%jdk_bin%\jlink @settings\jlink --output work\runtime\

echo Collecting installation image...
%jdk_bin%\jpackage @settings\jpackage --name cinecred --icon images\icon.ico --input app\ --runtime-image work\runtime\ --dest work\image\
del work\image\cinecred\cinecred.ico
copy resources\universal\LEGAL work\image\cinecred\

echo Assembling MSI package...
work\wix\heat.exe dir work\image\cinecred\ -nologo -ag -cg Files -dr INSTALLDIR -srd -sfrag -scom -sreg -indent 2 -o work\Files.wxs
work\wix\candle.exe resources\msi\*.wxs work\Files.wxs -nologo -arch x64 -o work\wixobj\
work\wix\light.exe work\wixobj\* -nologo -b work\image\cinecred -loc resources\msi\l10n\en-US.wxl -cultures:en-US -ext WixUIExtension -cc work\wixcab\ -spdb -o work\wixmsi\en-US.msi
work\wix\light.exe work\wixobj\* -nologo -b work\image\cinecred -loc resources\msi\l10n\de-DE.wxl -cultures:de-DE -ext WixUIExtension -cc work\wixcab\ -spdb -o work\wixmsi\de-DE.msi -reusecab
work\wix\torch.exe -nologo work\wixmsi\en-US.msi work\wixmsi\de-DE.msi -t language -o work\wixmst\de-DE.mst
copy work\wixmsi\en-US.msi work\out.msi
resources\msi\scripts\AddSubstorage.vbs work\out.msi work\wixmst\de-DE.mst 1031
resources\msi\scripts\SetPackageLanguage.vbs work\out.msi 1033,1031
move work\out.msi out\cinecred-@VERSION@-x86_64.msi

echo Cleaning up...
rmdir /S /Q work\
