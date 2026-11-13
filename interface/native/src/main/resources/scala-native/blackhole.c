#include <stdint.h>

/* An observable operand and memory clobber survive LLVM optimization and LTO.
 * noinline also keeps the external-call boundary visible to Interflow. */
__attribute__((noinline)) void scalanative_jmh_consume(int64_t value) {
  __asm__ __volatile__("" : : "r"(value) : "memory");
}

__attribute__((noinline)) void scalanative_jmh_consume_reference(void *value) {
  __asm__ __volatile__("" : : "r"(value) : "memory");
}
