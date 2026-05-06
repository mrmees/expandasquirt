#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
g++ -std=c++17 -Wall -Wextra -I. -I../firmware \
    test_main.cpp ../firmware/sensor_pipeline.cpp ../firmware/can_protocol.cpp ../firmware/sensor_health.cpp ../firmware/pct_decode.cpp ../firmware/maintenance_args.cpp \
    -o test_runner -lm
./test_runner
