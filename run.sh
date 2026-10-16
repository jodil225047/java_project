#!/bin/sh
# Builds the project and starts MediBridge. Any arguments are passed to the app (e.g. --port=9090 --reset)
set -e
cd "$(dirname "$0")"
mvn -q clean package
exec java -jar target/medibridge.jar "$@"
