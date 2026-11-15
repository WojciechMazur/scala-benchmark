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
// Host-only I/O thread. No benchmark Scala state is shared with it.
import fs from 'node:fs';
import { parentPort } from 'node:worker_threads';
parentPort.on('message', ({ bytes, channel, signal }) => {
  const state = new Int32Array(signal, 0, 1);
  try {
    const data = Buffer.from(bytes);
    let offset = 0;
    while (offset < data.length) offset += fs.writeSync(channel, data, offset, data.length - offset);
    Atomics.store(state, 0, 1);
  } catch (error) {
    const message = Buffer.from(String(error.message));
    new Uint8Array(signal, 4).set(message.subarray(0, signal.byteLength - 5));
    Atomics.store(state, 0, -1);
  } finally { Atomics.notify(state, 0); }
});
