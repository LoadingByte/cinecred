#!/bin/bash

source settings/general

jdk_dir="jdk-$JDK_MAJOR.$JDK_MINOR+$JDK_PATCH"

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
curl -L "https://github.com/AdoptOpenJDK/openjdk$JDK_MAJOR-binaries/releases/download/jdk-$JDK_MAJOR.$JDK_MINOR+$JDK_PATCH/OpenJDK${JDK_MAJOR}U-jdk_x64_${os}_hotspot_$JDK_MAJOR.${JDK_MINOR}_$JDK_PATCH.tar.gz" | tar -xzf -

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