#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
g++ -std=c++17 -Wall -Wextra -I. -I../expandasquirt-v4 \
    test_main.cpp ../expandasquirt-v4/sensor_pipeline.cpp ../expandasquirt-v4/can_protocol.cpp ../expandasquirt-v4/sensor_health.cpp ../expandasquirt-v4/pct_decode.cpp ../expandasquirt-v4/maintenance_args.cpp \
    -o test_runner -lm
./test_runner
