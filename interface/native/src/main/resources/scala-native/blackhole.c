/*
 * Copyright (c) 2026, Wojciech Mazur.
 *
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
#include <stdint.h>

/* An observable operand and memory clobber survive LLVM optimization and LTO.
 * noinline also keeps the external-call boundary visible to Interflow. */
__attribute__((noinline)) void scala_benchmark_consume(int64_t value) {
  __asm__ __volatile__("" : : "r"(value) : "memory");
}

__attribute__((noinline)) void scala_benchmark_consume_reference(void *value) {
  __asm__ __volatile__("" : : "r"(value) : "memory");
}
