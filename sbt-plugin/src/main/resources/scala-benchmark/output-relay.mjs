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
