#!/usr/bin/env bash
# Compiles and runs the framework-free Java checks. Needs only a JDK (17+), no Maven, no MySQL.
set -e
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
javac -d "$OUT" backend/src/main/java/com/cras/os/*.java backend/src/main/java/com/cras/service/AuditHasher.java verification/java/AlgorithmChecks.java
java -cp "$OUT" AlgorithmChecks
