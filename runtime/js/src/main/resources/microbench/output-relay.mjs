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
// A separate process can be killed even when an inherited output pipe blocks.
import fs from 'node:fs';
for (const [index, file] of process.argv.slice(2).entries()) {
  const input = fs.openSync(file, 'r');
  try {
    const buffer = Buffer.allocUnsafe(65536);
    let count;
    while ((count = fs.readSync(input, buffer, 0, buffer.length, null)) !== 0) {
      let offset = 0;
      while (offset < count) offset += fs.writeSync(index + 1, buffer, offset, count - offset);
    }
  } finally { fs.closeSync(input); }
}
