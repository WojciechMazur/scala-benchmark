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
