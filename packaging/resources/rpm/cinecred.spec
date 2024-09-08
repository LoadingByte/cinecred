Name: cinecred
Version: @VERSION@
Release: 1
License: GPLv3+
Group: Applications/Multimedia
Summary: @SLOGAN_EN@
URL: @URL@
Vendor: @VENDOR@

# Do not automatically populate Requires: and Provides:.
AutoReqProv: no
# Do not touch JARs.
%define __jar_repack %{nil}
# Do not strip binaries.
%global __os_install_post %{nil}

%description
@TEASER_EN@

%install
cp -r %{_sourcedir}/* %{buildroot}/

%files
/opt/cinecred/
# This symlink is listed separately to avoid a /usr/bin conflict error.
/usr/bin/cinecred
/usr/share/
