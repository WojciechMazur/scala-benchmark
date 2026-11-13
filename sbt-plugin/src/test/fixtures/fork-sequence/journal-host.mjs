import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
const count = path => fs.existsSync(path) ? Number(fs.readFileSync(path, 'utf8')) : 0;
globalThis.__forkSequenceJournal = {
  count,
  next(path) { const value = count(path) + 1; fs.writeFileSync(path, String(value)); return value; },
  flood() { const chunk = Buffer.alloc(65536, 120); for (let i = 0; i < 32; i++) fs.writeSync(1, chunk); },
  crash() { process.exit(23); }
};

// Fault injection stays in this test preload, outside generated measurement code.
const mode = process.env.SCALA_BENCHMARK_TEST_TRANSPORT;
const worker = process.argv.indexOf('--benchmark-worker-v3');
if (mode && worker >= 0 && process.env.SCALA_BENCHMARK_RESULT) {
  const request = JSON.parse(fs.readFileSync(process.argv[worker + 1], 'utf8'));
  if (request.suite.includes('ASequenceBenchmark')) {
    const write = fs.writeFileSync;
    fs.writeFileSync = function(file, data, ...options) {
      if (file !== process.env.SCALA_BENCHMARK_RESULT) return write.call(this, file, data, ...options);
      if (mode === 'nonregular') {
        const outcome = spawnSync('mkfifo', [file]);
        if (outcome.status !== 0) throw new Error('Cannot create test FIFO');
        return;
      }
      let value = String(data);
      if (mode === 'truncate' || mode === 'kill-write') value = value.slice(0, Math.floor(value.length / 2));
      else if (mode === 'trailing') value += 'X';
      else if (mode === 'incomplete') value = JSON.stringify(JSON.parse(value).slice(0, 1));
      write.call(this, file, value, ...options);
      if (mode === 'kill-write') process.kill(process.pid, 'SIGKILL');
    };
  }
}
