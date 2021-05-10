# Cinecred

Create film credits---without pain.

Visit the website at https://loadingbyte.com/cinecred/ for further information about the nature of this software, as well as manifold download options.

## Internal: Releasing

The following information is only relevant for building binaries of a new version and uploading them to the various distribution channels.

1. Run `gradle clean preparePackaging`.
   This will create three folders in `build/packaging/`, one for each OS.
2. Now copy the Windows folder onto a Windows machine and run the `package.bat` script there to build a Windows installer.
   Analogously proceed with MacOS and Linux, but use the `package.sh` script for these.
   * Note: On Linux, you will need tools for building DEB and RPM packages, or the script won't work.
   * Note: On Linux, you will need the `repo.loadingbyte.com` PGP key to sign the RPM package.
3. Collect the resulting packaged files into the `upload/` folder in this repository.
   There is 1 file for Windows and 1 file for MacOS. There are 4 files for Linux.
4. Run the `upload-nexus.sh` script to upload all binaries to `repo.loadingbyte.com`.
5. Run the `upload-aur.sh` script to prepare an update of the PKGBUILD script hosted on AUR.
   This script does not push to AUR for safety reasons.
   You will have to do that manually.
