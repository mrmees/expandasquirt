// tests/test_helpers.h â€” minimal C++ test harness, no external dependencies.

#pragma once
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <cstring>

static int g_test_passed = 0;
static int g_test_failed = 0;

#define TEST_CASE(name) \
    static void test_##name(); \
    struct test_registrar_##name { \
        test_registrar_##name() { \
            printf("[ RUN  ] " #name "\n"); \
            g_test_passed++; \
            test_##name(); \
            printf("[  OK  ] " #name "\n"); \
        } \
    } registrar_##name; \
    static void test_##name()

#define ASSERT_TRUE(x) do { \
    if (!(x)) { \
        printf("ASSERT_TRUE failed at %s:%d: " #x "\n", __FILE__, __LINE__); \
        g_test_failed++; \
        exit(1); \
    } \
} while (0)

#define ASSERT_EQ(a, b) do { \
    if ((a) != (b)) { \
        printf("ASSERT_EQ failed at %s:%d: " #a " != " #b "\n", __FILE__, __LINE__); \
        g_test_failed++; \
        exit(1); \
    } \
} while (0)

#define ASSERT_NEAR(a, b, tol) do { \
    double diff = std::abs((double)(a) - (double)(b)); \
    if (diff > (tol)) { \
        printf("ASSERT_NEAR failed at %s:%d: |%g - %g| = %g > %g\n", \
               __FILE__, __LINE__, (double)(a), (double)(b), diff, (double)(tol)); \
        g_test_failed++; \
        exit(1); \
    } \
} while (0)
