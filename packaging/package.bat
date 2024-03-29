@echo off

REM Read the settings file
FOR /F "tokens=1,2 delims==" %%G IN (settings\general) DO (set %%G=%%H)

mkdir work\
mkdir out\

echo Downloading and extracting Temurin...
set jdk_zip=OpenJDK%JDK_MAJOR%U-jdk_@ARCH_TEMURIN@_@OS@_hotspot_%JDK_MAJOR%%JDK_MINOR%_%JDK_PATCH%.zip
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
%jdk_bin%\jpackage @settings\jpackage @settings\jpackage-windows --name cinecred --icon images\icon.ico --input app\ --runtime-image work\runtime\ --dest work\image\
del work\image\cinecred\cinecred.ico
copy resources\universal\LEGAL work\image\cinecred\

echo Assembling ZIP archive...
powershell Compress-Archive work\image\Cinecred -DestinationPath out\cinecred-@VERSION@-@OS@-@ARCH@.zip

echo Assembling MSI package...
work\wix\heat.exe dir work\image\cinecred\ -nologo -ag -cg Files -dr INSTALLDIR -srd -sfrag -scom -sreg -indent 2 -o work\Files.wxs
work\wix\candle.exe resources\msi\*.wxs work\Files.wxs -nologo -arch @ARCH_WIX@ -o work\wixobj\
work\wix\light.exe work\wixobj\* -nologo -b work\image\cinecred -loc resources\msi\l10n\en-US.wxl -cultures:en-US -ext WixUIExtension -cc work\wixcab\ -spdb -o work\wixmsi\en-US.msi
work\wix\light.exe work\wixobj\* -nologo -b work\image\cinecred -loc resources\msi\l10n\cs-CZ.wxl -cultures:cs-CZ -ext WixUIExtension -cc work\wixcab\ -spdb -o work\wixmsi\cs-CZ.msi -reusecab
work\wix\light.exe work\wixobj\* -nologo -b work\image\cinecred -loc resources\msi\l10n\de-DE.wxl -cultures:de-DE -ext WixUIExtension -cc work\wixcab\ -spdb -o work\wixmsi\de-DE.msi -reusecab
work\wix\light.exe work\wixobj\* -nologo -b work\image\cinecred -loc resources\msi\l10n\fr-FR.wxl -cultures:fr-FR -ext WixUIExtension -cc work\wixcab\ -spdb -o work\wixmsi\fr-FR.msi -reusecab
work\wix\light.exe work\wixobj\* -nologo -b work\image\cinecred -loc resources\msi\l10n\zh-CN.wxl -cultures:zh-CN -ext WixUIExtension -cc work\wixcab\ -spdb -o work\wixmsi\zh-CN.msi -reusecab
work\wix\torch.exe -nologo work\wixmsi\en-US.msi work\wixmsi\cs-CZ.msi -t language -o work\wixmst\cs-CZ.mst
work\wix\torch.exe -nologo work\wixmsi\en-US.msi work\wixmsi\de-DE.msi -t language -o work\wixmst\de-DE.mst
work\wix\torch.exe -nologo work\wixmsi\en-US.msi work\wixmsi\fr-FR.msi -t language -o work\wixmst\fr-FR.mst
work\wix\torch.exe -nologo work\wixmsi\en-US.msi work\wixmsi\zh-CN.msi -t language -o work\wixmst\zh-CN.mst
copy work\wixmsi\en-US.msi work\out.msi
resources\msi\scripts\AddSubstorage.vbs work\out.msi work\wixmst\cs-CZ.mst 1029
resources\msi\scripts\AddSubstorage.vbs work\out.msi work\wixmst\de-DE.mst 1031
resources\msi\scripts\AddSubstorage.vbs work\out.msi work\wixmst\fr-FR.mst 1036
resources\msi\scripts\AddSubstorage.vbs work\out.msi work\wixmst\zh-CN.mst 2052
resources\msi\scripts\SetPackageLanguage.vbs work\out.msi 1033,1029,1031,1036,2052
move work\out.msi out\cinecred-@VERSION@-@ARCH@.msi

echo Cleaning up...
rmdir /S /Q work\
