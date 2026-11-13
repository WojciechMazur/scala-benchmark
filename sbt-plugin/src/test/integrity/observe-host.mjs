// Test-only preload: observe the production host without changing linked Scala code.
import assert from 'node:assert/strict';
import fs from 'node:fs';

let activeMethod;
let output = '';
const write = process.stdout.write;
function observeConsole(chunk) {
  output += String(chunk);
  let end;
  while ((end = output.indexOf('\n')) >= 0) {
    const line = output.slice(0, end);
    output = output.slice(end + 1);
    const match = line.match(/^# Running .*\.IntegrityBenchmark\.(\w+) /);
    if (match) activeMethod = match[1];
  }
}
process.stdout.write = function(chunk, ...args) {
  observeConsole(chunk);
  return write.call(this, chunk, ...args);
};

let clockReads = 0;
const originalNow = performance.now.bind(performance);
Object.defineProperty(performance, 'now', { value() { clockReads++; return originalNow(); } });
let host;
Object.defineProperty(globalThis, '__scalaBenchmarkHost', {
  configurable: true,
  get() { return host; },
  set(value) {
    host = value;
    const consoleWrite = value.consoleWrite;
    if (consoleWrite) value.consoleWrite = function(bytes, channel, budget) {
      if (channel === 1) observeConsole(Buffer.from(bytes).toString('utf8'));
      return consoleWrite.call(value, bytes, channel, budget);
    };
    const consume = value.consume;
    const exit = value.exit;
    const scalars = [];
    const references = [];
    const observedClocks = [];
    const counts = {};
    value.consume = function(result) {
      assert.ok(activeMethod, 'Consumption outside a selected benchmark');
      const index = counts[activeMethod] ?? 0;
      counts[activeMethod] = index + 1;
      const reference = activeMethod === 'returnedAllocation' || (activeMethod === 'explicitBlackhole' && index % 2 === 1);
      if (reference) references.push(result);
      else if (value.metadata.backend === 'wasm') {
        // Wasm passes boxed Longs as opaque GC references. Their payload is checked in the retained WAT.
        assert.equal(typeof result, 'object');
        scalars.push('<opaque-long>');
      } else scalars.push(String(result));
      observedClocks.push(clockReads);
      consume.call(value, result);
    };
    const verify = () => {
      const methods = ['primitiveBoolean', 'primitiveByte', 'primitiveShort', 'primitiveChar', 'primitiveInt',
        'inlinedInt', 'primitiveLong', 'primitiveFloat', 'primitiveDouble', 'returnedAllocation'];
      assert.deepEqual(counts, Object.fromEntries([...methods.map(name => [name, 7]), ['explicitBlackhole', 14]]));
      if (value.metadata.backend === 'wasm') assert.equal(scalars.length, 70);
      else {
        const expected = ['1', '42', '43', '44', '1698', '1718', '68940', '1109786624', '4631002238704156672', '70']
          .flatMap(value => Array(7).fill(value)).sort();
        assert.deepEqual(scalars.sort(), expected, 'Optimized scalar returns/explicit consumption changed');
      }
      assert.equal(references.length, 14, 'Missing returned/explicit allocations');
      assert.equal(new Set(references).size, 14, 'Allocation identity was collapsed');
      assert.ok(observedClocks.every(count => count > 0 && count < clockReads), 'Consumption escaped clock reads');
      fs.writeFileSync('integrity-host.json', JSON.stringify({
        backend: value.metadata.backend, node: process.version, v8: process.versions.v8, clockReads,
        counts, scalars, references: references.length, distinctReferences: new Set(references).size,
        consumeClockReads: observedClocks
      }, null, 2));
    };
    process.once('beforeExit', verify);
    value.exit = function(status) {
      if (status === 0) verify();
      exit.call(value, status);
    };
  }
});
