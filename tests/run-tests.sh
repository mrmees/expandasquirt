#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
g++ -std=c++17 -Wall -Wextra -I. -I../carduino-v4 test_main.cpp -o test_runner
./test_runner
