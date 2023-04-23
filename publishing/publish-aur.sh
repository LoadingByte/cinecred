#!/bin/bash

version=$(echo *linux*.tar.gz | cut -d'-' -f2)

git clone ssh://aur@aur.archlinux.org/cinecred.git
cp PKGBUILD cinecred/
cd cinecred/
makepkg --printsrcinfo > .SRCINFO
git add -A
git commit -m "Publish Cinecred $version"

echo "Now double-check everything and, if it's fine, do 'git push'."
