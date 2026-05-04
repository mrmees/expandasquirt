#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
g++ -std=c++17 -Wall -Wextra -I. -I../carduino-v4 \
    test_main.cpp ../carduino-v4/sensor_pipeline.cpp \
    -o test_runner -lm
./test_runner
