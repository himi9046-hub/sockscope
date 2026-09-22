#!/bin/sh
set -e
version=${1:?usage: packaging/build.sh VERSION}
cd "$(dirname "$0")/.."
mkdir -p dist

for format in deb rpm; do
    VERSION=$version go run github.com/goreleaser/nfpm/v2/cmd/nfpm@v2.46.0 \
        pkg --config packaging/nfpm.yaml --packager "$format" --target dist/
done

(cd app && ./gradlew installDist --console=plain -q)
jpackage --type deb --dest dist \
    --name sockscope --app-version "$version" --vendor himi \
    --description "Live network traffic per process on Linux" \
    --module-path app/build/install/sockscope/lib --module sockscope/sockscope.App \
    --icon packaging/sockscope.png \
    --linux-package-name sockscope-viewer --linux-shortcut \
    --linux-deb-maintainer 270933868+himi9046-hub@users.noreply.github.com \
    --linux-menu-group Network --linux-app-category net \
    --linux-package-deps sockscope-collector
ls -la dist
