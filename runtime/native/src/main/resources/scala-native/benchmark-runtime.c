/*
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The copyright holders
 * designate this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
#if defined(__linux__) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#pragma comment(lib, "shell32.lib")
#elif defined(__APPLE__)
#include <mach-o/dyld.h>
#include <unistd.h>
#include <signal.h>
#elif defined(__linux__)
#include <sys/types.h>
#include <unistd.h>
#include <signal.h>
#endif

// The UTF-8 command-line arguments of this process, without the program name.
// Windows passes main() its arguments in the ANSI code page, which loses
// characters outside it; elsewhere main() already receives them unchanged.
// Returns NULL when they are unavailable. The result is never freed.
char **scala_benchmark_arguments(int *count) {
  *count = 0;
#if defined(_WIN32)
  int total = 0;
  LPWSTR *wide = CommandLineToArgvW(GetCommandLineW(), &total);
  if (wide == NULL)
    return NULL;
  char **arguments = calloc(total > 1 ? (size_t)total - 1 : 1, sizeof(char *));
  if (arguments == NULL) {
    LocalFree(wide);
    return NULL;
  }
  for (int i = 1; i < total; i++) {
    int size = WideCharToMultiByte(CP_UTF8, 0, wide[i], -1, NULL, 0, NULL, NULL);
    arguments[i - 1] = size > 0 ? malloc((size_t)size) : NULL;
    if (arguments[i - 1] == NULL ||
        WideCharToMultiByte(CP_UTF8, 0, wide[i], -1, arguments[i - 1], size,
                            NULL, NULL) != size) {
      LocalFree(wide);
      return NULL;
    }
  }
  LocalFree(wide);
  *count = total - 1;
  return arguments;
#else
  return NULL;
#endif
}

// The UTF-8 temporary directory on Windows, where Scala Native derives
// java.io.tmpdir through the ANSI code page. Returns zero on success, a
// required buffer size on truncation, or -1 on error or elsewhere.
int scala_benchmark_temporary_directory(char *buffer, int length) {
#if defined(_WIN32)
  WCHAR path[MAX_PATH + 1];
  DWORD count = GetTempPathW(MAX_PATH + 1, path);
  if (count == 0 || count > MAX_PATH)
    return -1;
  int required = WideCharToMultiByte(CP_UTF8, 0, path, -1, NULL, 0, NULL, NULL);
  if (required == 0)
    return -1;
  if (required > length)
    return required;
  return WideCharToMultiByte(CP_UTF8, 0, path, -1, buffer, length, NULL,
                             NULL) == required
             ? 0
             : -1;
#else
  (void)buffer;
  (void)length;
  return -1;
#endif
}

// Returns zero on success, a required buffer size on truncation, or -1 on
// error.
int scala_benchmark_executable(char *buffer, int length) {
  if (buffer == NULL || length <= 0)
    return -1;
#if defined(_WIN32)
  DWORD capacity = 1024;
  for (;;) {
    WCHAR *path = malloc((size_t)capacity * sizeof(WCHAR));
    if (path == NULL)
      return -1;
    DWORD count = GetModuleFileNameW(NULL, path, capacity);
    if (count == 0) {
      free(path);
      return -1;
    }
    if (count == capacity) {
      free(path);
      if (capacity == 32768)
        return -1;
      capacity *= 2;
      continue;
    }
    int required = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, path, -1,
                                       NULL, 0, NULL, NULL);
    if (required == 0) {
      free(path);
      return -1;
    }
    if (required > length) {
      free(path);
      return required;
    }
    int written = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, path, -1,
                                      buffer, length, NULL, NULL);
    free(path);
    return written == required ? 0 : -1;
  }
#elif defined(__APPLE__)
  uint32_t size = (uint32_t)length;
  if (_NSGetExecutablePath(buffer, &size) != 0)
    return size <= INT32_MAX ? (int)size : -1;
  char *resolved = realpath(buffer, NULL);
  if (resolved == NULL)
    return -1;
  size_t required = strlen(resolved) + 1;
  if (required > (size_t)length) {
    free(resolved);
    return required <= INT32_MAX ? (int)required : -1;
  }
  memcpy(buffer, resolved, required);
  free(resolved);
  return 0;
#elif defined(__linux__)
  ssize_t n = readlink("/proc/self/exe", buffer, (size_t)length - 1);
  if (n < 0)
    return -1;
  if (n == length - 1)
    return length <= INT32_MAX / 2 ? length * 2 : -1;
  buffer[n] = '\0';
  return 0;
#else
#error "Unsupported Native benchmark platform"
#endif
}

#if !defined(_WIN32)
static int scala_benchmark_worker_group = 0;
#endif

// Bypass user shutdown hooks when a worker loses its coordinator.
void scala_benchmark_halt_worker(void) {
#if !defined(_WIN32)
  if (scala_benchmark_worker_group && getpgrp() == getpid()) kill(-getpid(), SIGKILL);
#endif
  _Exit(1);
}

int scala_benchmark_prepare_worker(void) {
#if defined(_WIN32)
  return 0; // The coordinator attaches the suspended-in-protocol worker to its job.
#else
  if (setpgid(0, 0) != 0 && getpgrp() != getpid()) return -1;
  scala_benchmark_worker_group = 1;
  return 0;
#endif
}

int64_t scala_benchmark_process_id(void) {
#if defined(_WIN32)
  return (int64_t)GetCurrentProcessId();
#else
  return (int64_t)getpid();
#endif
}

int64_t scala_benchmark_attach_fork(int64_t pid) {
#if defined(_WIN32)
  HANDLE job = CreateJobObjectW(NULL, NULL);
  if (!job) return -1;
  JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits;
  memset(&limits, 0, sizeof(limits));
  limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
  if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits))) {
    CloseHandle(job); return -1;
  }
  HANDLE child = OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, FALSE, (DWORD)pid);
  if (!child) { CloseHandle(job); return -1; }
  BOOL assigned = AssignProcessToJobObject(job, child);
  CloseHandle(child);
  if (!assigned) { CloseHandle(job); return -1; }
  return (int64_t)(intptr_t)job;
#else
  return 0;
#endif
}

void scala_benchmark_release_fork(int64_t pid, int64_t token) {
#if defined(_WIN32)
  if (token != 0 && token != -1) CloseHandle((HANDLE)(intptr_t)token);
#else
  if (pid > 0) kill(-(pid_t)pid, SIGKILL);
#endif
}
