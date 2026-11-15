/*
 * Copyright (c) 2026, Scala Benchmark contributors.
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
// Progress is a lease granted by the supervisor's monotonic clock. Re-reading
// an unchanged file must never renew it. Decimal strings preserve 64-bit values.
const maximum = 9223372036854775807n;
const startup = 30000000000n;
export class ProgressDeadline {
  constructor(timeoutNanos, clock = () => process.hrtime.bigint()) {
    this.clock = clock;
    this.startedAt = clock();
    const timeout = BigInt(timeoutNanos);
    if (timeout < 0n || timeout > maximum) throw new Error('Invalid fork timeout');
    this.budget = timeout > startup ? timeout : startup;
    this.sequence = -1n;
  }
  receive(text) {
    const fields = /^3 ([0-9]+) ([0-9]+)\n$/.exec(text);
    if (!fields) return false;
    const sequence = BigInt(fields[1]);
    const budget = BigInt(fields[2]);
    if (sequence > maximum || sequence <= this.sequence || budget <= 0n || budget > maximum) return false;
    this.sequence = sequence;
    this.budget = budget;
    this.startedAt = this.clock();
    return true;
  }
  expired() { return this.clock() - this.startedAt >= this.budget; }
}
