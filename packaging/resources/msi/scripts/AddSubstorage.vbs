' Adds a transform MST to an MSI as a substorage.
' Usage: <MSI> <MST> <Substorage Name>
' Adapted from: https://github.com/microsoft/Windows-classic-samples/blob/main/Samples/Win7Samples/sysmgmt/msi/scripts/WiSubStg.vbs

Option Explicit

Dim installer : Set installer = Wscript.CreateObject("WindowsInstaller.Installer")
Dim database : Set database = installer.OpenDatabase(Wscript.Arguments(0), 1)
Dim view : Set view = database.OpenView("SELECT Name, Data FROM _Storages")
Dim record : Set record = installer.CreateRecord(2)
record.StringData(1) = Wscript.Arguments(2)
view.Execute record
record.SetStream 2, Wscript.Arguments(1)
view.Modify 3, record
database.Commit
