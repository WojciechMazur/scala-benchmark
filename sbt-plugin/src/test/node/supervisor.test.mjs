import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const launcher = fileURLToPath(new URL('../../../../runtime/js/src/main/resources/scala-benchmark/node-host.mjs', import.meta.url));
function alive(pid) { try { process.kill(pid, 0); return true; } catch (error) { if (error.code === 'ESRCH') return false; throw error; } }
function fixture(t, body, owner) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'benchmark-supervisor-test-'));
  const module = path.join(directory, 'worker.mjs');
  const request = path.join(directory, 'request.json');
  fs.writeFileSync(module, body);
  fs.writeFileSync(request, '{}');
  const child = spawn(process.execPath, [launcher, '--supervise', module, request,
    path.join(directory, 'result.json'), path.join(directory, 'progress'), '100000000', ...(owner ? [String(owner)] : [])], { stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '', workerPid;
  const pid = new Promise((resolve, reject) => {
    child.on('error', reject);
    child.stdout.on('data', chunk => {
      output += chunk;
      const match = /WORKER_PID ([0-9]+)/.exec(output);
      if (match) { workerPid = Number(match[1]); resolve(workerPid); }
    });
  });
  child.stderr.on('data', chunk => { output += chunk; });
  const closed = new Promise((resolve, reject) => { child.on('close', (code, signal) => resolve({ code, signal })); child.on('error', reject); });
  t.after(() => {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
    if (workerPid && alive(workerPid)) process.kill(workerPid, 'SIGKILL');
    fs.rmSync(directory, { recursive: true, force: true });
  });
  return { child, pid, closed, output: () => output };
}
const announce = "console.log('WORKER_PID ' + process.pid); globalThis.__scalaBenchmarkHost.progress('3 0 100000000\\n');\n";

test('supervisor kills non-returning work when the monotonic lease expires', { timeout: 5000 }, async t => {
  const probe = fixture(t, announce + 'Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0);');
  const pid = await probe.pid;
  const result = await probe.closed;
  assert.equal(result.code, 1, probe.output());
  assert.equal(alive(pid), false, 'Expired benchmark worker survived');
});

for (const [name, record] of [['duplicate', '3 0 99999999999\n'], ['partial', '3 1 99999999999']]) {
  test(`${name} progress cannot keep a worker alive`, { timeout: 5000 }, async t => {
    const probe = fixture(t, announce.replace('100000000', '1000000000') +
      `setTimeout(() => setInterval(() => globalThis.__scalaBenchmarkHost.progress(${JSON.stringify(record)}), 10), 100);`);
    const pid = await probe.pid;
    const result = await probe.closed;
    assert.equal(result.code, 1, probe.output());
    assert.equal(alive(pid), false, 'Invalid progress kept benchmark worker alive');
  });
}

test('interrupting the supervisor also terminates its benchmark worker', { timeout: 5000 }, async t => {
  const probe = fixture(t, "console.log('WORKER_PID ' + process.pid); Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0);");
  const pid = await probe.pid;
  probe.child.kill('SIGTERM');
  const result = await probe.closed;
  assert.ok(result.code !== 0 || result.signal, probe.output());
  assert.equal(alive(pid), false, 'Interrupted supervisor left benchmark worker alive');
});

test('successful child exit without results is rejected and temporary files are removed', { timeout: 5000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'benchmark-missing-result-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const module = path.join(directory, 'worker.mjs');
  fs.writeFileSync(module, `
    const host = globalThis.__scalaBenchmarkHost;
    if (host.arguments[0] === '--benchmark-worker-v3') process.exit(0);
    else {
      let rejected = false;
      try { host.fork('{}', '100000000'); } catch (error) { rejected = error.code === 'ENOENT'; }
      if (!rejected) throw new Error('Missing worker results were accepted');
      console.log('MISSING_RESULT_REJECTED');
    }
  `);
  const child = spawn(process.execPath, [launcher, module], { env: { ...process.env, TMPDIR: directory }, stdio: ['ignore', 'pipe', 'pipe'] });
  t.after(() => { if (child.exitCode === null) child.kill('SIGKILL'); });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk; });
  child.stderr.on('data', chunk => { output += chunk; });
  const code = await new Promise((resolve, reject) => { child.on('close', resolve); child.on('error', reject); });
  assert.equal(code, 0, output);
  assert.match(output, /MISSING_RESULT_REJECTED/);
  assert.deepEqual(fs.readdirSync(directory).sort(), ['worker.mjs']);
});

test('hard-killing the supervisor terminates blocked synchronous work', { timeout: 5000 }, async t => {
  const probe = fixture(t, "console.log('WORKER_PID ' + process.pid); while (true) {};");
  const pid = await probe.pid;
  probe.child.kill('SIGKILL');
  const limit = Date.now() + 3000;
  while (alive(pid) && Date.now() < limit) await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal(alive(pid), false, 'Hard-killed supervisor left synchronous benchmark work alive');
  await probe.closed;
});

test('hard-killing a synchronous coordinator terminates supervisor and worker', { timeout: 7000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'benchmark-owner-death-'));
  const module = path.join(directory, 'worker.mjs');
  const workerFile = path.join(directory, 'worker.pid');
  const supervisorFile = path.join(directory, 'supervisor.pid');
  fs.writeFileSync(module, `
    import fs from 'node:fs';
    const host = globalThis.__scalaBenchmarkHost;
    if (host.arguments[0] === '--benchmark-worker-v3') {
      fs.writeFileSync(${JSON.stringify(workerFile)}, String(process.pid));
      fs.writeFileSync(${JSON.stringify(supervisorFile)}, String(process.ppid));
      while (true) {}
    } else host.fork('{}', '10000000000');
  `);
  const child = spawn(process.execPath, [launcher, module], { stdio: 'ignore' });
  let worker, supervisor;
  t.after(() => {
    for (const pid of [child.pid, supervisor, worker]) if (pid && alive(pid)) process.kill(pid, 'SIGKILL');
    fs.rmSync(directory, { recursive: true, force: true });
  });
  let limit = Date.now() + 3000;
  while (!fs.existsSync(supervisorFile) && Date.now() < limit) await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(fs.existsSync(supervisorFile), 'Worker never initialized');
  worker = Number(fs.readFileSync(workerFile, 'utf8'));
  supervisor = Number(fs.readFileSync(supervisorFile, 'utf8'));
  const closed = new Promise(resolve => child.once('close', resolve));
  child.kill('SIGKILL');
  await closed;
  limit = Date.now() + 3000;
  while ((alive(worker) || alive(supervisor)) && Date.now() < limit) await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal(alive(worker), false, 'Coordinator death left worker alive');
  assert.equal(alive(supervisor), false, 'Coordinator death left supervisor alive');
});

test('nonzero worker exit rejects even an already written result', { timeout: 5000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'benchmark-crash-result-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const module = path.join(directory, 'worker.mjs');
  fs.writeFileSync(module, `
    const host = globalThis.__scalaBenchmarkHost;
    if (host.arguments[0] === '--benchmark-worker-v3') {
      host.complete('{"iterations": []}');
      process.exit(23);
    } else {
      let rejected = false;
      try { host.fork('{}', '100000000'); }
      catch (error) { rejected = /Benchmark fork exited with 23/.test(error.message); }
      if (!rejected) throw new Error('A failed worker result was accepted');
      console.log('CRASH_RESULT_REJECTED');
    }
  `);
  const child = spawn(process.execPath, [launcher, module], {
    env: { ...process.env, TMPDIR: directory }, stdio: ['ignore', 'pipe', 'pipe']
  });
  t.after(() => { if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL'); });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk; });
  child.stderr.on('data', chunk => { output += chunk; });
  const code = await new Promise((resolve, reject) => { child.on('close', resolve); child.on('error', reject); });
  assert.equal(code, 0, output);
  assert.match(output, /CRASH_RESULT_REJECTED/);
  assert.deepEqual(fs.readdirSync(directory).sort(), ['worker.mjs']);
});


test('supervisor rejects an owner lost before supervisor initialization', { timeout: 5000 }, async t => {
  const probe = fixture(t, "console.log('WORKER_PID ' + process.pid); while (true) {};", 2147483647);
  const result = await probe.closed;
  assert.equal(result.code, 1, probe.output());
});

for (const channel of [1, 2]) {
  test(`blocked inherited fd ${channel} cannot trap fork output replay`, { timeout: 7000 }, async t => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'benchmark-output-blocked-'));
    const module = path.join(directory, 'worker.mjs');
    const outcome = path.join(directory, 'outcome');
    fs.writeFileSync(module, `
      import fs from 'node:fs';
      const host = globalThis.__scalaBenchmarkHost;
      if (host.arguments[0] === '--benchmark-worker-v3') {
        const buffer = Buffer.alloc(65536, 120);
        for (let i = 0; i < 32; i++) fs.writeSync(${channel}, buffer);
        host.complete(JSON.stringify({failureVersion: 1, benchmarkType: 'FixtureFailure', message: 'injected primary', completed: []}));
      } else {
        try { host.fork('{}', '100000000'); throw new Error('Blocked output accepted'); }
        catch (error) {
          if (error.code !== 'SCALA_BENCHMARK_OUTPUT') throw error;
          fs.writeFileSync(${JSON.stringify(outcome)}, error.message);
          process.exit(0);
        }
      }
    `);
    const child = spawn(process.execPath, [launcher, module], {
      env: { ...process.env, TMPDIR: directory }, stdio: ['ignore', 'pipe', 'pipe']
    });
    // Neither output pipe is consumed. The result is recorded through a separate file.
    t.after(() => {
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
      child.stdout.destroy(); child.stderr.destroy();
      fs.rmSync(directory, { recursive: true, force: true });
    });
    const code = await new Promise((resolve, reject) => { child.on('exit', resolve); child.on('error', reject); });
    assert.equal(code, 0);
    assert.match(fs.readFileSync(outcome, 'utf8'), /output relay/);
    assert.match(fs.readFileSync(outcome, 'utf8'), /FixtureFailure: injected primary/);
    assert.deepEqual(fs.readdirSync(directory).sort(), ['outcome', 'worker.mjs']);
  });
}

test('worker death during a partial result write rejects the payload', { timeout: 5000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'benchmark-partial-result-'));
  const module = path.join(directory, 'worker.mjs');
  fs.writeFileSync(module, `
    import fs from 'node:fs';
    const host = globalThis.__scalaBenchmarkHost;
    if (host.arguments[0] === '--benchmark-worker-v3') {
      fs.writeFileSync(process.env.SCALA_BENCHMARK_RESULT, '[{"score":');
      process.kill(process.pid, 'SIGKILL');
    } else {
      let rejected = false;
      try { host.fork('{}', '100000000'); }
      catch (error) { rejected = /Benchmark fork exited with/.test(error.message); }
      if (!rejected) throw new Error('Interrupted serialization was accepted');
    }
  `);
  const child = spawn(process.execPath, [launcher, module], {
    env: { ...process.env, TMPDIR: directory }, stdio: 'ignore'
  });
  t.after(() => {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
    fs.rmSync(directory, { recursive: true, force: true });
  });
  const code = await new Promise((resolve, reject) => { child.on('exit', resolve); child.on('error', reject); });
  assert.equal(code, 0);
  assert.deepEqual(fs.readdirSync(directory).sort(), ['worker.mjs']);
});

for (const scenario of ['success', 'crash', 'missing', 'timeout', 'gap']) {
  test(`fork event ${scenario} retains only published records and cleans its directory`, { timeout: 8000 }, async t => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'benchmark-event-records-'));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    const module = path.join(directory, 'events.mjs');
    fs.writeFileSync(module, `
      import fs from 'node:fs';
      import path from 'node:path';
      const host = globalThis.__scalaBenchmarkHost;
      if (host.arguments[0] === '--benchmark-worker-v3') {
        console.error('EVENT_PRIMARY_CAUSE');
        host.event(0, 'first');
        host.event(${scenario === 'gap' ? 2 : 1}, 'second');
        fs.writeFileSync(path.join(process.env.SCALA_BENCHMARK_DIRECTORY, 'event.pending'), 'partial');
        ${scenario === 'crash' ? 'process.exit(23);' : scenario === 'missing' ? 'process.exit(0);' :
          scenario === 'timeout' ? "host.progress('3 0 100000000\\n'); while (true) {}" : "host.complete('[]');"}
      } else {
        let rejected = false;
        try { host.fork(JSON.stringify({ events: true, directOutput: false }), '1000000000'); }
        catch (error) {
          rejected = true;
          if (${scenario !== 'gap'} && !error.benchmarkOutput?.includes('EVENT_PRIMARY_CAUSE')) throw new Error('Lost primary diagnostic');
        }
        if (rejected !== ${scenario !== 'success'}) throw new Error('Incorrect fork status');
        const events = host.takeEvents();
        if (${scenario !== 'gap'} && JSON.stringify(events) !== '["first","second"]') throw new Error('Lost event prefix: ' + events);
        if (host.takeEvents().length) throw new Error('Events were delivered twice');
        console.log('EVENT_RECORDS_OK');
      }
    `);
    const child = spawn(process.execPath, [launcher, module], {
      env: { ...process.env, TMPDIR: directory }, stdio: ['ignore', 'pipe', 'pipe']
    });
    t.after(() => { if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL'); });
    let output = '';
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { output += chunk; });
    const code = await new Promise((resolve, reject) => { child.on('close', resolve); child.on('error', reject); });
    assert.equal(code, 0, output);
    assert.match(output, /EVENT_RECORDS_OK/);
    assert.deepEqual(fs.readdirSync(directory).sort(), ['events.mjs']);
  });
}

for (const scenario of ['success', 'crash', 'missing', 'timeout']) {
  test(`separate binary user channels survive ${scenario} and unlink before draining`, { timeout: 8000 }, async t => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'benchmark-user-streams-'));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    const module = path.join(directory, 'user.mjs');
    fs.writeFileSync(module, `
      import fs from 'node:fs';
      const host = globalThis.__scalaBenchmarkHost;
      const bytes = Array.from({length: 20000}, (_, index) => index & 255);
      if (host.arguments[0] === '--benchmark-worker-v3') {
        host.userWrite(bytes, 1);
        host.userWrite(bytes.slice().reverse(), 2);
        host.userWrite(Array.from(Buffer.from('FRAMEWORK-λ')), 3);
        console.error('BOOTSTRAP-DIAGNOSTIC');
        ${scenario === 'crash' ? 'process.exit(23);' : scenario === 'missing' ? 'process.exit(0);' :
          scenario === 'timeout' ? "host.progress('3 0 100000000\\n'); while(true) {}" : "host.complete('[]');"}
      } else {
        let rejected = false;
        try {
          const result = JSON.parse(host.fork(JSON.stringify({stdio: true, directOutput: false}), '1000000000'));
          if (!result.output.includes('BOOTSTRAP-DIAGNOSTIC')) throw new Error('Lost bootstrap output');
          if (result.output.includes('FRAMEWORK')) throw new Error('Mixed output provenance');
        } catch(error) { rejected = true; }
        if (rejected !== ${scenario !== 'success'}) throw new Error('Unexpected status');
        if (fs.readdirSync(${JSON.stringify(directory)}).some(name => name.startsWith('scala-benchmark-'))) throw new Error('RPC directory retained');
        const read = channel => {
          const chunks = []; let next;
          while ((next = host.readUserOutput(channel)).length) {
            if (next.length > 8192) throw new Error('Unbounded output chunk');
            chunks.push(Buffer.from(next));
          }
          return Buffer.concat(chunks);
        };
        try {
          if (!read(1).equals(Buffer.from(bytes))) throw new Error('Corrupted stdout');
          if (!read(2).equals(Buffer.from(bytes.slice().reverse()))) throw new Error('Corrupted stderr');
          if (read(3).toString('utf8') !== 'FRAMEWORK-λ') throw new Error('Corrupted framework output');
        } finally { host.releaseUserOutput(); }
        if (host.readUserOutput(1).length) throw new Error('Repeated user output');
        console.log('USER_CHANNELS_OK');
      }
    `);
    const child = spawn(process.execPath, [launcher, module], {
      env: { ...process.env, TMPDIR: directory }, stdio: ['ignore', 'pipe', 'pipe']
    });
    t.after(() => { if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL'); });
    let output = '';
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { output += chunk; });
    const code = await new Promise((resolve, reject) => { child.on('close', resolve); child.on('error', reject); });
    assert.equal(code, 0, output);
    assert.match(output, /USER_CHANNELS_OK/);
    assert.deepEqual(fs.readdirSync(directory).sort(), ['user.mjs']);
  });
}
