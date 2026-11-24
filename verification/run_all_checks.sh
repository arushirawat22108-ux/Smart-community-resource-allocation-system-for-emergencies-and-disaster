#!/usr/bin/env bash
# Runs every check that needs no database. Usage: bash verification/run_all_checks.sh
set -e
cd "$(dirname "$0")/.."
echo "### 1/4 Python unit tests";     python3 verification/test_python.py 2>&1 | tail -4
echo "### 2/4 SQL logic (SQLite)";    python3 verification/sql_logic_check.py | tail -3
echo "### 3/4 Banker's algorithm (Python reference)"; (cd os_sim && python3 banker.py --test)
echo "### 4/4 Java algorithms (scheduler, Banker, audit hash)"; bash verification/run_java_checks.sh | tail -8
