#!/bin/bash

source settings/general

jdk_dir="jdk-$JDK_MAJOR+$JDK_PATCH"

usage='Usage: <mac|linux>'
if [[ $# -ne 1 ]]; then
  echo "$0 $usage"
  exit 1
fi
os="$1"
if [[ "$os" == mac ]]; then
  types=(pkg)
  jdk_bin="$jdk_dir/Contents/Home/bin"
elif [[ "$os" == linux ]]; then
  types=(deb rpm)
  jdk_bin="$jdk_dir/bin"
else
  echo "$usage"
  exit 1
fi

echo "Downloading and extracting AdoptOpenJDK..."
curl -L "https://github.com/adoptium/temurin$JDK_MAJOR-binaries/releases/download/jdk-$JDK_MAJOR+$JDK_PATCH/OpenJDK$JDK_MAJOR-jdk_x64_${os}_hotspot_${JDK_MAJOR}_$JDK_PATCH.tar.gz" | tar -xzf -

echo "Running jlink..."
"$jdk_bin/jlink" @settings/jlink

for type in "${types[@]}"; do
  echo "Running jpackage to build a $type package..."
  while true; do
    "$jdk_bin/jpackage" @settings/jpackage "@settings/jpackage-$os" --type "$type"
    if [[ $? -eq 0 ]]; then break; fi
    read -n 1 -r -p "jpackage failed to build a $type package for the reason listed above. Maybe build tools are missing on your system. Try again? (y/n) "
    echo
    if [[ ! "$REPLY" =~ ^[yY]$ ]]; then break; fi
  done
done

echo "Cleaning up..."
rm -rf runtime/
rm -rf "$jdk_dir"

if [[ "$os" == mac ]]; then
  echo "Renaming pkg package..."
  mv ./*.pkg "$(basename ./*.pkg .pkg)-x86_64.pkg"
elif [[ "$os" == linux ]]; then
  echo "Signing rpm package..."
  rpm --addsign ./*.rpm

  echo "Building tar.gz package..."
  tar_gz=cinecred-@VERSION@-linux-x86_64.tar.gz
  mkdir -p tree/
  ar -p ./*.deb data.tar.xz | tar -xJC tree/ --strip-components=2
  mv tree/cinecred/lib/cinecred-Cinecred.desktop tree/cinecred/cinecred.desktop
  tar -czf "$tar_gz" -C tree/ cinecred/
  rm -rf tree/

  echo "Generating PKGBUILD..."
  sed "s/{{SHA_256_HASH}}/$(sha256sum "$tar_gz" | cut -d ' ' -f 1)/g" misc/PKGBUILD-template > PKGBUILD
fi
