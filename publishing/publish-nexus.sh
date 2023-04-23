#!/bin/bash

read -p 'Nexus username: ' user
read -sp 'Nexus password: ' pass
echo

# Upload to Maven repository
version=$(echo *.msi | cut -d'-' -f2)
mvn_args=""
mvn_idx=1
for ext in msi pkg zip tar.gz; do
  for file in *.$ext; do
    mvn_args="$mvn_args -F maven2.asset$mvn_idx=@$file -F maven2.asset$mvn_idx.extension=$ext -Fmaven2.asset$mvn_idx.classifier=$(basename $file .$ext | cut -d'-' -f3-)"
    ((mvn_idx+=1))
  done
done
curl -u $user:$pass \
  POST 'https://repo.loadingbyte.com/service/rest/v1/components?repository=maven-releases' \
  -F maven2.groupId=com.loadingbyte \
  -F maven2.artifactId=cinecred \
  -F maven2.version=$version \
  -F maven2.generate-pom=false \
  $mvn_args

# Upload to APT repository
curl -u $user:$pass \
  POST 'https://repo.loadingbyte.com/service/rest/v1/components?repository=apt-releases' \
  -F apt.asset=@$(echo *.deb)

# Upload to YUM repository
rpm_file=$(echo *.rpm)
curl -u $user:$pass \
  POST 'https://repo.loadingbyte.com/service/rest/v1/components?repository=yum-releases' \
  -F yum.directory=Packages/cinecred \
  -F yum.asset=@$rpm_file \
  -F yum.asset.filename=$rpm_file
