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
REM Collect all languages for which we have .wxl files in three variables (en-US always comes first):
REM lang_tags = en-US de-DE ...
REM lang_codes = 1033 1031 ...
REM lang_tags_and_codes = en-US/1033 de-DE/1031 ...
set lang_tags=
set lang_codes=
set lang_tags_and_codes=
FOR %%F IN (resources\msi\l10n\*) DO (
    FOR /F "tokens=1 USEBACKQ" %%C IN (`powershell "(Select-XML -Path %%F -XPath '//ns:String[@Id=''LanguageCode'']' -Namespace @{ ns = 'http://schemas.microsoft.com/wix/2006/localization'; }).Node.'#text'"`) DO (
        call :append_lang %%~nF %%C
    )
)
REM Generate a .wxs file that lists all installed files
work\wix\heat.exe dir work\image\cinecred\ -nologo -ag -cg Files -dr INSTALLDIR -srd -sfrag -scom -sreg -indent 2 -o work\Files.wxs
REM Compile all .wxs files to .wxo files
work\wix\candle.exe resources\msi\*.wxs work\Files.wxs -nologo -arch @ARCH_WIX@ -o work\wixobj\
REM Assemble a separate .msi for each language
FOR %%T IN (%lang_tags%) DO (
    IF "%%T" == "en-US" (set extra_arg=) else (set extra_arg=-reusecab)
    work\wix\light.exe work\wixobj\* -nologo -b work\image\cinecred -loc resources\msi\l10n\%%T.wxl -cultures:%%T -ext WixUIExtension -cc work\wixcab\ -spdb -o work\wixmsi\%%T.msi %extra_arg%
)
REM Obtain .mst transformations from the en-US .msi to each other language's .msi
FOR %%T IN (%lang_tags%) DO (
    IF not "%%T" == "en-US" (work\wix\torch.exe -nologo work\wixmsi\en-US.msi work\wixmsi\%%T.msi -t language -o work\wixmst\%%T.mst)
)
REM Add the transformations as substorages to the en-US .msi
copy work\wixmsi\en-US.msi work\out.msi
FOR %%P IN (%lang_tags_and_codes%) DO (FOR /F "tokens=1,2 delims=/" %%T IN ("%%P") DO (
    IF not "%%T" == "en-US" (resources\msi\scripts\AddSubstorage.vbs work\out.msi work\wixmst\%%T.mst %%U)
))
REM Write all available language codes into the .msi
resources\msi\scripts\SetPackageLanguage.vbs work\out.msi %lang_codes%
move work\out.msi out\cinecred-@VERSION@-@ARCH@.msi

echo Cleaning up...
rmdir /S /Q work\

goto :eof


:append_lang
IF not defined lang_tags (
    set lang_tags=%1& set lang_codes=%2& set lang_tags_and_codes=%1/%2
) ELSE (IF "%1" == "en-US" (
    set lang_tags=%1,%lang_tags%& set lang_codes=%2,%lang_codes%& set lang_tags_and_codes=%1/%2,%lang_tags_and_codes%
) ELSE (
    set lang_tags=%lang_tags%,%1& set lang_codes=%lang_codes%,%2& set lang_tags_and_codes=%lang_tags_and_codes%,%1/%2
))
