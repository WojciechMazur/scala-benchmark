#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#if defined(__APPLE__)
#include <mach-o/dyld.h>
#endif

/* An observable operand and memory clobber survive LLVM optimization and LTO.
 * noinline also keeps the external-call boundary visible to Interflow. */
__attribute__((noinline)) void scalanative_jmh_consume(int64_t value) {
    __asm__ __volatile__("" : : "r"(value) : "memory");
}

__attribute__((noinline)) void scalanative_jmh_consume_reference(void *value) {
    __asm__ __volatile__("" : : "r"(value) : "memory");
}

int scalanative_jmh_executable(char *buffer, int length) {
#if defined(__APPLE__)
    uint32_t size = (uint32_t)length;
    if (_NSGetExecutablePath(buffer, &size) != 0)
        return -1;
    char *resolved = realpath(buffer, NULL);
    if (resolved == NULL)
        return -1;
    size_t n = 0;
    while (resolved[n] != '\0') {
        if (n + 1 >= (size_t)length) {
            free(resolved);
            return -1;
        }
        buffer[n] = resolved[n];
        n++;
    }
    buffer[n] = '\0';
    free(resolved);
    return 0;
#elif defined(__linux__)
    ssize_t n = readlink("/proc/self/exe", buffer, (size_t)length - 1);
    if (n < 0 || n >= length - 1)
        return -1;
    buffer[n] = '\0';
    return 0;
#else
#error "Native JMH currently supports Linux and macOS"
#endif
}
