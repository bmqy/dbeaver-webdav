#!/usr/bin/env bash
set -euo pipefail

release_tag="${1:-}"
release_version="${release_tag#v}"

if [[ -z "${release_tag}" || "${release_tag}" == "${release_version}" ]]; then
  echo "Usage: $0 vMAJOR.MINOR.PATCH" >&2
  exit 1
fi

if ! [[ "${release_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Release tag must use vMAJOR.MINOR.PATCH format, got: ${release_tag}" >&2
  exit 1
fi

maven_version="${release_version}-SNAPSHOT"
osgi_version="${release_version}.qualifier"
export maven_version osgi_version

perl -0pi -e 's{(<revision>)[^<]+(</revision>)}{$1$ENV{maven_version}$2}' \
  pom.xml

perl -0pi -e 's{Bundle-Version: [^\r\n]+}{Bundle-Version: $ENV{osgi_version}}' \
  dbeaver-webdav-backup-plugin/META-INF/MANIFEST.MF

perl -0pi -e 's{(<feature\s+id="net\.bmqy\.dbeaver\.webdav\.backup\.feature"\s+label="DBeaver WebDAV 备份"\s+version=")[^"]+(")}{$1$ENV{osgi_version}$2}' \
  dbeaver-webdav-backup-plugin.feature/feature.xml

echo "Synced release version ${release_tag}: Maven ${maven_version}, OSGi ${osgi_version}"
