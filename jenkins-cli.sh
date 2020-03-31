#!/bin/sh

set -ue

REPO=$(dirname $0)

"$REPO/gradlew" -p "$REPO" :jenkins-cli:installDist
"$REPO/jenkins-cli/build/install/jenkins-cli/bin/jenkins-cli" "$@"
