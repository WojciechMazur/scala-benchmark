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
