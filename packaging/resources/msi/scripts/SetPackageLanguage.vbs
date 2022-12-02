' Sets the package language property, which designates first the primary language and then all available language transforms (comma-separated).
' Usage: <MSI> <Languages>
' Adapted from: https://github.com/microsoft/Windows-classic-samples/blob/main/Samples/Win7Samples/sysmgmt/msi/scripts/WiLangId.vbs

Option Explicit

Dim installer : Set installer = Wscript.CreateObject("WindowsInstaller.Installer")
Dim database : Set database = installer.OpenDatabase(Wscript.Arguments(0), 1)
Dim sumInfo : Set sumInfo = database.SummaryInformation(1)
Dim template : template = sumInfo.Property(7)
Dim sepIdx: sepIdx = InStr(1, template, ";", vbTextCompare)
Dim platform : If sepIdx = 0 Then platform = ";" Else platform = Left(template, sepIdx)
sumInfo.Property(7) = platform & Wscript.Arguments(1)
sumInfo.Persist
database.Commit
