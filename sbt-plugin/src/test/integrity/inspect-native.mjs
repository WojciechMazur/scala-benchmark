import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export function inspectNative(ir, assembly) {
  const definitions = [...ir.matchAll(/^define[^\n]*\{[\s\S]*?^\}/gm)].map(match => match[0]);
  const measure = definitions.find(text => /BenchmarkMain\$\$anon\$\d+D7measure/.test(text.split('\n')[0]));
  assert.ok(measure, 'Missing optimized measurement definition');
  const methods = ['primitiveBoolean', 'primitiveByte', 'primitiveShort', 'primitiveChar', 'primitiveInt',
    'primitiveLong', 'primitiveFloat', 'primitiveDouble', 'returnedAllocation', 'explicitBlackhole', 'observableUnit'];
  const blocks = assembly.split(/(?=^[0-9a-f]+ <[^\n]+>:\r?$)/m);
  const machineMeasure = blocks.find(text => /^\w+ <[^\n]*BenchmarkMain\$\$anon\$\d+D7measure/m.test(text));
  assert.ok(machineMeasure, 'Missing final measurement machine code');
  const calls = machineMeasure.split('\n').filter(line => /\b(?:bl|callq?)\s/.test(line));
  for (const method of methods) {
    const definition = definitions.find(text => text.split('\n')[0].includes('IntegrityBenchmarkD') && text.split('\n')[0].includes(method));
    assert.ok(definition, `Missing optimized workload ${method}`);
    assert.match(definition.split('\n')[0], /noinline/, `Lost requested noinline boundary: ${method}`);
    assert.ok(calls.some(line => line.includes(method)), `No final measured call to ${method}`);
    if (method.startsWith('primitive') || method === 'returnedAllocation') {
      const lines = measure.split('\n');
      const sites = lines.flatMap((line, index) => {
        const match = line.match(/(%[\w.]+) = call/);
        return match && line.includes('IntegrityBenchmarkD') && line.includes(method) ? [[match[1], index]] : [];
      });
      assert.ok(sites.length > 0, `No LLVM call sites for ${method}`);
      for (const [result, index] of sites) {
        const origins = new Set([result]);
        let consumed = false;
        for (const line of lines.slice(index + 1, index + 15)) {
          const carriesResult = [...origins].some(value => new RegExp(value + '(?![\\w.])').test(line));
          if (carriesResult && /call void.*(?:BlackholeK?D7consume|scalanative_jmh_consume)/.test(line)) { consumed = true; break; }
          const cast = line.match(/(%[\w.]+) = (?:sext|zext|bitcast|phi) /);
          if (cast && carriesResult) origins.add(cast[1]);
          if (carriesResult && /br i1/.test(line))
            for (const label of line.matchAll(/label (%[\w.]+)/g)) origins.add(label[1]);
        }
        assert.ok(consumed, `Returned value bypasses consumption: ${method}`);
      }
    }
  }
  assert.match(measure, /add i64[^\n]*, 37/, 'Inlined workload arithmetic missing from measurement');
  const work = definitions.find(text => text.split('\n')[0].includes('D12primitiveInt'));
  assert.match(work, /mul i64/);
  assert.match(work, /add i64[^\n]*, 17/);
  const allocation = definitions.find(text => text.split('\n')[0].includes('D18returnedAllocation'));
  assert.match(allocation, /call[^\n]*scalanative_GC_alloc/);
  assert.match(allocation, /store atomic i64/);
  const explicit = definitions.find(text => text.split('\n')[0].includes('D17explicitBlackhole'));
  assert.match(explicit, /call void @"scalanative_jmh_consume"/);
  assert.match(explicit, /call void @"scalanative_jmh_consume_reference"/);
  assert.ok(calls.filter(line => /scalanative_nano_time|clock_gettime|QueryPerformanceCounter/.test(line)).length >= 2, 'Clock boundaries missing from final measured code');
  assert.ok(calls.some(line => line.includes('scalanative_jmh_consume')), 'Final measurement lost consumption calls');
  assert.doesNotMatch(measure, /call[^\n]*(?:Function0.*apply|ScriptedClock|java\.lang\.reflect)/, 'Indirect/test dispatch in hot loop');
  return { methods, returnedValuesFeedConsumption: true, nativeAllocationRetained: true, finalCallsAndClocksRetained: true };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const directory = process.argv[2];
  const ir = fs.readFileSync(path.join(directory, 'optimized-functions.ll'), 'utf8');
  const assembly = fs.readFileSync(path.join(directory, 'disassembly.txt'), 'utf8');
  const result = inspectNative(ir, assembly);
  assert.throws(() => inspectNative(ir.replaceAll('scalanative_GC_alloc', 'removed_allocation'), assembly));
  assert.throws(() => inspectNative(ir.replaceAll('BlackholeD7consume', 'removed_consumption').replaceAll('BlackholeKD7consume', 'removed_consumption').replaceAll('scalanative_jmh_consume', 'removed_consumption'), assembly));
  assert.throws(() => inspectNative(ir, assembly.replace(/scalanative_nano_time|clock_gettime|QueryPerformanceCounter/g, 'removed_clock')));
  assert.throws(() => inspectNative(ir, assembly.replaceAll('primitiveInt', 'removedWork')), /No final measured call/);
  result.rejectsRemovedWorkAllocationConsumptionAndClocks = true;
  fs.writeFileSync(path.join(directory, 'inspection.json'), JSON.stringify(result, null, 2));
}
