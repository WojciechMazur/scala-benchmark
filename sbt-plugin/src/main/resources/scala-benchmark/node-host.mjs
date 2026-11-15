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
// Host services are deliberately external to the Scala.js optimizer and work
// with both JavaScript and WebAssembly modules. No exported Scala methods needed.
import fs from 'node:fs';
import { ProgressDeadline } from './progress-deadline.mjs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { Worker } from 'node:worker_threads';
import { fileURLToPath, pathToFileURL } from 'node:url';

const launcher = fileURLToPath(import.meta.url);
const argv = process.argv.slice(2);
if (argv[0] === '--supervise') {
  const [module, request, result, progress, timeoutNanos, ownerPid] = argv.slice(1);
  const deadline = new ProgressDeadline(timeoutNanos);
  const owner = ownerPid === undefined ? process.ppid : Number(ownerPid);
  let timedOut = false;
  const child = spawn(process.execPath, [...process.execArgv, launcher, module,
    '--benchmark-worker-v3', request], {
    detached: process.platform !== 'win32',
    stdio: ['inherit', 'inherit', 'inherit', 'pipe'],
    env: { ...process.env, SCALA_BENCHMARK_RESULT: result, SCALA_BENCHMARK_PROGRESS: progress, SCALA_BENCHMARK_LIFELINE: '3', SCALA_BENCHMARK_DIRECTORY: path.dirname(request), SCALA_BENCHMARK_GROUP: process.platform === 'win32' ? '0' : '1' }
  });
  let workerExited = false;
  let ownerLost = false;
  let treeStopped = false;
  const killTree = () => {
    if (treeStopped) return;
    treeStopped = true;
    if (process.platform === 'win32') {
      if (!workerExited) spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore', timeout: 1000 });
    } else if (child.pid) {
      try { process.kill(-child.pid, 'SIGKILL'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
    }
  };
  const stopWorker = () => {
    timedOut = true;
    killTree(); if (!workerExited) child.kill('SIGKILL');
  };
  process.once('SIGINT', stopWorker);
  process.once('SIGTERM', stopWorker);
  process.once('exit', () => { killTree(); if (!workerExited) child.kill('SIGKILL'); });
  const timer = setInterval(() => {
    // spawnSync blocks the coordinator's event loop. Detect its death here;
    // the worker separately watches this supervisor through an owned pipe.
    ownerLost = process.ppid !== owner;
    try { process.kill(owner, 0); } catch (error) { ownerLost ||= error.code === 'ESRCH'; }
    if (ownerLost) { stopWorker(); return; }
    if (fs.existsSync(progress)) {
      deadline.receive(fs.readFileSync(progress, 'utf8'));
    }
    if (deadline.expired()) {
      timedOut = true;
      console.error('Scala.js benchmark fork timed out');
      killTree(); child.kill('SIGKILL');
    }
  }, 25);
  child.on('error', error => { console.error(error); clearInterval(timer); process.exitCode = 1; });
  child.on('exit', () => { workerExited = true; killTree(); });
  child.on('close', code => {
    clearInterval(timer);
    if (ownerLost && path.basename(path.dirname(request)).startsWith('scala-benchmark-'))
      fs.rmSync(path.dirname(request), { recursive: true, force: true });
    process.exitCode = timedOut ? 1 : (code ?? 1);
  });
} else {
  const [module, ...args] = argv;
  if (args[0] === '--benchmark-worker-v3' && process.env.SCALA_BENCHMARK_LIFELINE) {
    const guard = new Worker(new URL('./worker-lifeline.mjs', import.meta.url), {
      workerData: { fd: Number(process.env.SCALA_BENCHMARK_LIFELINE), group: process.env.SCALA_BENCHMARK_GROUP === '1', directory: process.env.SCALA_BENCHMARK_DIRECTORY }
    });
    guard.on('error', () => process.exit(1));
    await new Promise((resolve, reject) => {
      guard.once('message', resolve);
      guard.once('error', reject);
      guard.once('exit', () => reject(new Error('Benchmark lifeline stopped before initialization')));
    });
    guard.unref();
  }
  const metadata = { os: os.platform(), arch: process.arch, engine: 'V8', engineVersion: process.versions.v8, osRelease: os.release(),
    cpu: os.cpus()[0]?.model ?? 'unknown', nodeOptions: JSON.stringify(process.execArgv) };
  let consumed;
  let forkEvents = [];
  let userOutput = [];
  const userFiles = ['user-stdout.bin', 'user-stderr.bin', 'framework.bin'];
  const workerOutputs = [];
  const releaseUserOutput = () => {
    const descriptors = userOutput; userOutput = [];
    for (const descriptor of descriptors) if (descriptor !== undefined) fs.closeSync(descriptor);
  };
  process.once('exit', () => {
    for (const descriptor of [...userOutput, ...workerOutputs])
      if (descriptor !== undefined) try { fs.closeSync(descriptor); } catch { /* process owns these handles */ }
  });
  let consoleWriter;
  let consoleWriterFailed = false;
  const validateOutput = file => {
    if (fs.existsSync(file) && !fs.statSync(file).isFile() && file !== '/dev/null' && file.toUpperCase() !== 'NUL')
      throw new Error('Benchmark output destination must be a regular file: ' + file);
    fs.accessSync(fs.existsSync(file) ? file : path.dirname(path.resolve(file)), fs.constants.W_OK);
  };
  globalThis.__scalaBenchmarkHost = {
    arguments: args[0] === '--benchmark-worker-v3'
      ? [args[0], fs.readFileSync(args[1], 'utf8')] : args,
    metadata,
    runtimeVersion: process.version,
    gcAvailable: typeof globalThis.gc === 'function',
    requestGC() {
      if (typeof globalThis.gc !== 'function') throw new Error('Host collection is unavailable; start Node.js with --expose-gc');
      globalThis.gc();
    },
    sleep(milliseconds) {
      if (milliseconds > 0) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
    },
    stringify(value) { return JSON.stringify(value); },
    parse(text) { return JSON.parse(text); },
    // An externally observable, bounded root; never accumulate all consumed data.
    consume(value) { consumed = value; },
    get consumed() { return consumed; },
    validateOutput,
    write(file, text) { validateOutput(file); fs.writeFileSync(file, text, 'utf8'); },
    consoleWrite(bytes, channel, timeoutNanos) {
      const fail = message => { const error = new Error(message); error.code = 'SCALA_BENCHMARK_OUTPUT'; throw error; };
      if (consoleWriterFailed) fail('Console output channel is unusable');
      if (!consoleWriter) {
        consoleWriter = new Worker(new URL('./console-writer.mjs', import.meta.url));
        consoleWriter.on('error', () => { consoleWriterFailed = true; });
        consoleWriter.unref();
      }
      const signal = new SharedArrayBuffer(1028);
      const state = new Int32Array(signal, 0, 1);
      consoleWriter.postMessage({ bytes, channel, signal });
      const budget = BigInt(timeoutNanos);
      const milliseconds = Number(budget > 2147483647000000n ? 2147483647n : (budget + 999999n) / 1000000n);
      const waited = Atomics.wait(state, 0, 0, Math.max(1, milliseconds));
      if (waited === 'timed-out' || Atomics.load(state, 0) !== 1) {
        consoleWriterFailed = true;
        const message = Buffer.from(signal, 4).toString('utf8').split('\0')[0];
        fail(message || 'Console output timed out');
      }
    },
    progress(message) {
      if (process.env.SCALA_BENCHMARK_PROGRESS)
        fs.writeFileSync(process.env.SCALA_BENCHMARK_PROGRESS, message);
    },
    event(sequence, text) {
      const directory = process.env.SCALA_BENCHMARK_DIRECTORY;
      if (!directory || !Number.isSafeInteger(sequence) || sequence < 0) throw new Error('Invalid worker event');
      const pending = path.join(directory, 'event.pending');
      fs.writeFileSync(pending, text, 'utf8');
      fs.renameSync(pending, path.join(directory, 'event-' + String(sequence).padStart(10, '0')));
    },
    userWrite(bytes, channel) {
      const index = channel - 1;
      if (index < 0 || index > 2) throw new Error('Invalid user output channel');
      if (!process.env.SCALA_BENCHMARK_DIRECTORY) throw new Error('Missing user output directory');
      const descriptor = workerOutputs[index] ??= fs.openSync(path.join(process.env.SCALA_BENCHMARK_DIRECTORY, userFiles[index]), 'a');
      const data = Buffer.from(bytes);
      let offset = 0;
      while (offset < data.length) offset += fs.writeSync(descriptor, data, offset, data.length - offset);
    },
    userClose(channel) {
      const index = channel - 1;
      if (index < 0 || index > 2) throw new Error('Invalid user output channel');
      const descriptor = workerOutputs[index]; workerOutputs[index] = undefined;
      if (descriptor !== undefined) fs.closeSync(descriptor);
    },
    readUserOutput(channel) {
      if (channel < 1 || channel > 3) throw new Error('Invalid user output channel');
      const descriptor = userOutput[channel - 1];
      if (descriptor === undefined) return [];
      const bytes = Buffer.allocUnsafe(8192);
      const count = fs.readSync(descriptor, bytes, 0, bytes.length, null);
      return Array.from(bytes.subarray(0, count));
    },
    releaseUserOutput,
    takeEvents() { const result = forkEvents; forkEvents = []; return result; },
    complete(text) {
      if (!process.env.SCALA_BENCHMARK_RESULT) throw new Error('Missing worker result path');
      fs.writeFileSync(process.env.SCALA_BENCHMARK_RESULT, text, 'utf8');
    },
    fork(request, timeoutNanos) {
      if (userOutput.some(descriptor => descriptor !== undefined))
        throw new Error('Previous benchmark user output was not released');
      forkEvents = [];
      const streams = JSON.parse(request).stdio === true;
      const observing = JSON.parse(request).events === true;
      const directOutput = JSON.parse(request).directOutput !== false;
      const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'scala-benchmark-'));
      const requestFile = path.join(directory, 'request.json');
      const result = path.join(directory, 'result.json');
      const progress = path.join(directory, 'deadline');
      const output = path.join(directory, 'stdout.txt');
      const errors = path.join(directory, 'stderr.txt');
      try {
        fs.writeFileSync(requestFile, request, 'utf8');
        if (streams) for (const file of userFiles) fs.writeFileSync(path.join(directory, file), Buffer.alloc(0));
        // The asynchronous supervisor enforces iteration deadlines while this
        // synchronous coordinator is waiting, including for non-returning code.
        let outcome;
        let descriptor, errorDescriptor;
        try {
          descriptor = fs.openSync(output, 'w');
          errorDescriptor = fs.openSync(errors, 'w');
          outcome = spawnSync(process.execPath, [...process.execArgv, launcher, '--supervise',
            module, requestFile, result, progress, String(timeoutNanos), String(process.pid)], { stdio: ['inherit', descriptor, errorDescriptor] });
        } finally {
          if (descriptor !== undefined) fs.closeSync(descriptor);
          if (errorDescriptor !== undefined) fs.closeSync(errorDescriptor);
        }
        if (directOutput && (fs.statSync(output).size > 0 || fs.statSync(errors).size > 0)) {
          const budget = BigInt(timeoutNanos);
          const milliseconds = Number(((budget + 999999n) / 1000000n) > 2147483647n ? 2147483647n : ((budget + 999999n) / 1000000n));
          const relay = spawnSync(process.execPath, [fileURLToPath(new URL('./output-relay.mjs', import.meta.url)), output, errors], {
            stdio: ['ignore', 'inherit', 'inherit'], timeout: Math.max(1, milliseconds), killSignal: 'SIGKILL'
          });
          if (relay.error || relay.status !== 0) {
            let benchmarkFailure = '';
            if (outcome.status === 0 && fs.existsSync(result) && fs.lstatSync(result).isFile()) {
              try {
                const text = fs.readFileSync(result, 'utf8');
                if (text.trimStart().startsWith('{')) {
                  const record = JSON.parse(text);
                  if (record.failureVersion === 1) benchmarkFailure = `; ${record.benchmarkType}: ${record.message}`;
                }
              } catch { /* The runtime validates malformed records when output is healthy. */ }
            }
            const error = new Error(`Benchmark output relay failed or timed out; worker status ${outcome.status ?? outcome.signal}${benchmarkFailure}`);
            error.code = 'SCALA_BENCHMARK_OUTPUT';
            throw error;
          }
        }
        if (outcome.error) throw outcome.error;
        if (outcome.status !== 0) throw new Error(`Benchmark fork exited with ${outcome.status ?? outcome.signal}`);
        if (!fs.lstatSync(result).isFile()) throw new Error('Missing or non-regular benchmark result file');
        return JSON.stringify({ result: fs.readFileSync(result, 'utf8'),
          output: directOutput ? '' : fs.readFileSync(output, 'utf8') + fs.readFileSync(errors, 'utf8') });
      } catch (error) {
        // A captured destination is replayed by Scala, including when no result can be returned.
        if (!directOutput) error.benchmarkOutput = [output, errors]
          .filter(file => fs.existsSync(file) && fs.lstatSync(file).isFile())
          .map(file => fs.readFileSync(file, 'utf8')).join('');
        throw error;
      } finally {
        try {
          if (observing) {
            const files = fs.readdirSync(directory).filter(name => /^event-[0-9]{10}$/.test(name)).sort();
            forkEvents = files.map((name, index) => {
              const file = path.join(directory, name);
              if (name !== 'event-' + String(index).padStart(10, '0') || !fs.lstatSync(file).isFile() || fs.statSync(file).size > 64 * 1024 * 1024)
                throw new Error('Invalid fork event sequence');
              return fs.readFileSync(file, 'utf8');
            });
          }
        } finally {
          try {
            if (streams) {
              // Open before unlink: draining remains bounded and crash-safe without retaining the RPC directory.
              for (const file of userFiles) {
                const target = path.join(directory, file);
                if (!fs.lstatSync(target).isFile()) throw new Error('Invalid user output file');
                userOutput.push(fs.openSync(target, 'r'));
              }
            }
          } catch (error) { releaseUserOutput(); throw error; }
          finally { fs.rmSync(directory, { recursive: true, force: true }); }
        }
      }
    },
    exit(status) { process.exit(status); }
  };
  await import(pathToFileURL(module).href);
}
