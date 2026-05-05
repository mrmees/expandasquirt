#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
g++ -std=c++17 -Wall -Wextra -I. -I../carduino-v4 \
    test_main.cpp ../carduino-v4/sensor_pipeline.cpp ../carduino-v4/can_protocol.cpp ../carduino-v4/sensor_health.cpp ../carduino-v4/util/pct_decode.cpp ../carduino-v4/maintenance_args.cpp \
    -o test_runner -lm
./test_runner
