#!/bin/bash

source settings/general

jdk_dir="work/jdk-$JDK_MAJOR$JDK_MINOR+$JDK_PATCH"

if [[ "@OS@" == mac ]]; then
  jdk_bin="$jdk_dir/Contents/Home/bin"
  jpackage_args="--name Cinecred --icon images/icon.icns @settings/jpackage-mac"
elif [[ "@OS@" == linux ]]; then
  jdk_bin="$jdk_dir/bin"
  jpackage_args="--name cinecred"
  for cmd in tar fakeroot dpkg-deb rpmbuild rpmsign; do
    if ! command -v $cmd > /dev/null; then
      missing_cmds+=($cmd)
    fi
  done
  if [[ ! -z "$missing_cmds" ]]; then
    echo "The following required programs are not installed: ${missing_cmds[@]}"
    exit 1
  fi
else
  exit 1
fi

mkdir work/ out/

echo "Downloading and extracting Temurin..."
curl -L "https://github.com/adoptium/temurin$JDK_MAJOR-binaries/releases/download/jdk-$JDK_MAJOR$JDK_MINOR+$JDK_PATCH/OpenJDK${JDK_MAJOR}U-jdk_@ARCH_TEMURIN@_@OS@_hotspot_$JDK_MAJOR${JDK_MINOR}_$JDK_PATCH.tar.gz" | tar -xzf - -C work/

echo "Collecting minimized JRE..."
"$jdk_bin/jlink" @settings/jlink $jlink_args --output work/runtime/

echo "Collecting installation image..."
"$jdk_bin/jpackage" @settings/jpackage $jpackage_args --input app/ --runtime-image work/runtime/ --dest work/image/

if [[ "@OS@" == mac ]]; then
  cp resources/universal/LEGAL work/image/Cinecred.app/Contents/

  echo "Assembling TAR.GZ archive..."
  tar -czf out/cinecred-@VERSION@-@OS@-@ARCH@.tar.gz -C work/image/ Cinecred.app/

  echo "Assembling PKG package..."
  pkgbuild --root work/image/ --component-plist resources/pkg/component.plist --identifier Cinecred --install-location /Applications work/Cinecred.pkg
  productbuild --distribution resources/pkg/distribution.dist --package-path work/ --resources images/ out/cinecred-@VERSION@-@ARCH@.pkg
elif [[ "@OS@" == linux ]]; then
  cp resources/universal/LEGAL work/image/cinecred/
  rm work/image/cinecred/lib/cinecred.png

  echo "Assembling TAR.GZ archive..."
  mkdir -p work/targz/cinecred/
  cp -r work/image/cinecred/* resources/linux/cinecred.desktop images/* "$_"
  tar -czf out/cinecred-@VERSION@-@OS@-@ARCH@.tar.gz -C work/targz/ cinecred/

  echo "Assembling AUR PKGBUILD script..."
  sed "s/{{SHA_256_HASH}}/$(sha256sum out/*.tar.gz | cut -d " " -f 1)/g" resources/aur/PKGBUILD > out/PKGBUILD

  echo "Collecting DEB/RPM package tree..."
  mkdir -p work/tree/opt/cinecred/
  cp -r work/image/cinecred/* "$_"
  mkdir -p work/tree/usr/share/applications/
  cp resources/linux/cinecred.desktop "$_"
  mkdir -p work/tree/usr/share/icons/hicolor/scalable/apps/
  cp images/cinecred.svg "$_"
  mkdir -p work/tree/usr/share/icons/hicolor/48x48/apps/
  cp images/cinecred.png "$_"
  mkdir -p work/tree/usr/bin/
  ln -s /opt/cinecred/bin/cinecred "$_"

  echo "Assembling DEB package..."
  cp -r work/tree/ work/deb/
  mkdir -p work/deb/DEBIAN/
  cp resources/deb/control "$_"
  echo "Installed-Size: $(du -s work/deb/opt/ | cut -f1)" >> work/deb/DEBIAN/control
  fakeroot dpkg-deb --build work/deb out/cinecred_@VERSION@-1_@ARCH_DEBIAN@.deb

  echo "Assembling and signing RPM package..."
  rpmbuild --quiet -bb resources/rpm/cinecred.spec --define "%_sourcedir $(pwd)/work/tree" --define "%_topdir $(pwd)/work/rpm" --define "%_rpmdir $(pwd)/out" --define "%_rpmfilename cinecred-@VERSION@-1.@ARCH@.rpm"
  rpmsign --addsign out/*.rpm
fi

echo "Cleaning up..."
rm -rf work/
