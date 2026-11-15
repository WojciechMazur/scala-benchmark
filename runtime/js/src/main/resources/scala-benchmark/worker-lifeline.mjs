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
// Runs on its own thread so blocked synchronous JS/Wasm cannot delay EOF.
import { workerData, parentPort } from 'node:worker_threads';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';

const stop = () => {
  if (workerData.directory && path.basename(workerData.directory).startsWith('scala-benchmark-')) {
    try { fs.rmSync(workerData.directory, { recursive: true, force: true }); } catch { /* Terminate even if cleanup fails. */ }
  }
  process.kill(workerData.group ? -process.pid : process.pid, 'SIGKILL');
};
try {
  const pipe = new net.Socket({ fd: workerData.fd, readable: true, writable: false });
  pipe.on('end', stop);
  pipe.on('error', stop);
  pipe.resume();
  parentPort.postMessage('ready');
} catch {
  stop();
}
