#!/bin/bash

set -e
set -o verbose
set -o xtrace
export SHELLOPTS
echo "SHELLOPTS=${SHELLOPTS}"

# Optional argument $1 is the group.
GROUPARG=$1
echo "GROUPARG=$GROUPARG"
# These are all the Java projects at https://github.com/plume-lib
if [[ "${GROUPARG}" == "bcel-util" ]]; then PACKAGES=("${GROUPARG}"); fi
if [[ "${GROUPARG}" == "bibtex-clean" ]]; then PACKAGES=("${GROUPARG}"); fi
if [[ "${GROUPARG}" == "html-pretty-print" ]]; then PACKAGES=("${GROUPARG}"); fi
if [[ "${GROUPARG}" == "icalavailable" ]]; then PACKAGES=("${GROUPARG}"); fi
if [[ "${GROUPARG}" == "lookup" ]]; then PACKAGES=("${GROUPARG}"); fi
if [[ "${GROUPARG}" == "multi-version-control" ]]; then PACKAGES=("${GROUPARG}"); fi
if [[ "${GROUPARG}" == "options" ]]; then PACKAGES=("${GROUPARG}"); fi
if [[ "${GROUPARG}" == "plume-util" ]]; then PACKAGES=("${GROUPARG}"); fi
if [[ "${GROUPARG}" == "require-javadoc" ]]; then PACKAGES=("${GROUPARG}"); fi
if [[ "${GROUPARG}" == "signature-util" ]]; then PACKAGES=("${GROUPARG}"); fi
if [[ "${GROUPARG}" == "all" ]] || [[ "${GROUPARG}" == "" ]]; then
    if java -version 2>&1 | grep version | grep 1.8 ; then
        PACKAGES=(bcel-util bibtex-clean html-pretty-print icalavailable lookup multi-version-control options plume-util require-javadoc)
    else
        PACKAGES=(bcel-util bibtex-clean html-pretty-print icalavailable lookup multi-version-control options plume-util)
    fi
fi
if [ -z ${PACKAGES+x} ]; then
  echo "Bad group argument '${GROUPARG}'"
  exit 1
fi
echo "PACKAGES=" "${PACKAGES[@]}"


if [ -d "/tmp/plume-scripts" ] ; then
  (cd /tmp/plume-scripts && git pull -q)
else
  (cd /tmp && (git clone --depth 1 -q https://github.com/plume-lib/plume-scripts.git || git clone --depth 1 -q https://github.com/plume-lib/plume-scripts.git))
fi

echo "initial CHECKERFRAMEWORK=$CHECKERFRAMEWORK"
export CHECKERFRAMEWORK="${CHECKERFRAMEWORK:-$(pwd -P)}"
echo "CHECKERFRAMEWORK=$CHECKERFRAMEWORK"

## Build the Checker Framework
if [ -d "$CHECKERFRAMEWORK" ] ; then
  # Fails if not currently on a branch
  git -C "$CHECKERFRAMEWORK" pull || true
else
  /tmp/plume-scripts/git-clone-related typetools checker-framework "$CHECKERFRAMEWORK" \
    || /tmp/plume-scripts/git-clone-related typetools checker-framework "$CHECKERFRAMEWORK"
fi
# This also builds annotation-tools
(cd "$CHECKERFRAMEWORK" && ./checker/bin-devel/build.sh downloadjdk)

echo "PACKAGES=" "${PACKAGES[@]}"
for PACKAGE in "${PACKAGES[@]}"; do
  echo "PACKAGE=${PACKAGE}"
  PACKAGEDIR="/tmp/${PACKAGE}"
  rm -rf "${PACKAGEDIR}"
  /tmp/plume-scripts/git-clone-related plume-lib "${PACKAGE}" "${PACKAGEDIR}"
  echo "About to call ./gradlew --console=plain -PcfLocal assemble"
  (cd "${PACKAGEDIR}" && CHECKERFRAMEWORK=$CHECKERFRAMEWORK ./gradlew --console=plain -PcfLocal assemble)
done
