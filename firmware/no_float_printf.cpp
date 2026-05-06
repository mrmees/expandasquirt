#ifdef ARDUINO

// Symbol-pinning trick to keep newlib-nano's float-aware printf code OUT of
// the linked binary, saving ~5 KB.
//
// Newlib-nano uses _printf_float as a "pin" symbol: nano-vfprintf.c contains a
// reference that the linker resolves *if* anyone in the program calls a
// printf-family function with %f/%g/%e. That reference pulls in
// nano-vfprintf_float.o (which then pulls _dtoa_r and a chain of double-math
// helpers — together about 5 KB of .text).
//
// Our firmware does not use %f/%g/%e anywhere. But the Renesas core's itoa.c
// includes dtostrf.c.impl, which references _printf_float regardless of
// whether it's actually called. That stale reference alone is enough to drag
// the float-aware printf into the binary.
//
// Providing our own (no-op) _printf_float definition here satisfies the
// linker requirement without pulling the implementation. Any actual call to
// _printf_float — there shouldn't be any in our firmware — is a 2-byte
// no-op-and-return.
//
// If you ever add a snprintf("%f", ...) somewhere, deleting this file will
// re-enable libc's float formatter and add ~5 KB back to the binary.

extern "C" void _printf_float(void) {
}

#endif
