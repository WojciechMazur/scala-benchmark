import fs from 'node:fs';
import { spawn } from 'node:child_process';
globalThis.__benchmarkTreeHost = {
  start(path) {
    const child = spawn('sh', ['-c', 'sleep 60 & grand=$!; printf "%s %s" "$$" "$grand" > "$1"; wait', 'benchmark-tree', path], { stdio: 'inherit' });
    child.unref();
    const started = Date.now();
    const ready = () => fs.existsSync(path) && /^[0-9]+ [0-9]+$/.test(fs.readFileSync(path, 'utf8').trim());
    while (!ready() && Date.now() - started < 2000) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1);
    if (!ready()) throw new Error('Tree child did not start');
  },
  crash() { process.exit(23); }
};
