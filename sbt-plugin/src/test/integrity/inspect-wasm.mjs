import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export function inspectWasm(wat, glue) {
  const functions = wat.split(/(?=^  \(func )/m).filter(text => text.startsWith('  (func '));
  const find = name => {
    const body = functions.find(text => text.split('\n')[0].includes(name));
    assert.ok(body, `Missing optimized Wasm function ${name}`);
    return body;
  };
  const owner = '$f.scala.scalanative.jmh.examples.IntegrityBenchmark.';
  const measure = find('BenchmarkMain$$anon$1.measure_');
  const names = ['primitiveBoolean', 'primitiveByte', 'primitiveShort', 'primitiveChar', 'primitiveInt',
    'primitiveLong', 'primitiveFloat', 'primitiveDouble', 'returnedAllocation', 'explicitBlackhole', 'observableUnit'];
  for (const name of names) {
    find(owner + name + '_');
    assert.ok(measure.includes('call ' + owner + name + '_'), `Missing measured workload call ${name}`);
    if (name.startsWith('primitive') || name === 'returnedAllocation') {
      const lines = measure.split('\n');
      for (let index = 0; index < lines.length; index++) {
        if (!lines[index].trim().startsWith('call ' + owner + name + '_')) continue;
        const following = lines.slice(index + 1, index + 5).map(line => line.trim());
        assert.ok(following.some(line => /^call .*Blackhole(?:Sink\$)?\.consume/.test(line)), `Wasm result bypasses consumption: ${name}`);
        assert.ok(!following.slice(0, following.findIndex(line => line.includes('.consume'))).includes('drop'), `Wasm result dropped: ${name}`);
      }
    }
  }
  assert.match(measure, /i32\.const 37[\s\S]*i64\.mul[\s\S]*i32\.wrap_i64[\s\S]*i32\.add/, 'Inlined workload missing from measurement');
  assert.match(find(owner + 'primitiveInt_'), /i64\.mul[\s\S]*i32\.wrap_i64[\s\S]*i32\.add/);
  assert.match(find(owner + 'primitiveLong_'), /i64\.mul[\s\S]*i64\.mul[\s\S]*i64\.add/);
  assert.match(find(owner + 'primitiveDouble_'), /f64\.convert_i64_s[\s\S]*f64\.add/);
  assert.match(find(owner + 'primitiveFloat_'), /f32\.convert_i64_s[\s\S]*f32\.add/);
  assert.match(find(owner + 'returnedAllocation_'), /call \$new\..*IntegrityValue[\s\S]*i64\.mul[\s\S]*i64\.add[\s\S]*call \$ct\..*IntegrityValue/);
  assert.match(find('$ct.scala.scalanative.jmh.examples.IntegrityValue.<init>_'), /struct\.set .*IntegrityValue.*value/);
  assert.match(find('$new.scala.scalanative.jmh.examples.IntegrityValue '), /struct\.new .*IntegrityValue/);
  assert.ok((measure.match(/call \$m\.java\.lang\.System\$NanoTime\$/g) ?? []).length >= 2, 'Clock reads missing from measurement');
  assert.ok(measure.includes('Blackhole.consume_'), 'Returned values no longer consumed');
  assert.match(glue, /__scalaBenchmarkHost\.consume\(/);
  return { methods: names, pureArithmeticRetained: true, allocationAndPayloadRetained: true, measuredCallsAndConsumptionRetained: true };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const directory = process.argv[2];
  const wat = fs.readFileSync(path.join(directory, 'main.wat'), 'utf8');
  const glue = fs.readFileSync(path.join(directory, 'main.js'), 'utf8');
  const result = inspectWasm(wat, glue);
  assert.throws(() => inspectWasm(wat.replaceAll('call $f.scala.scalanative.jmh.examples.IntegrityBenchmark.primitiveLong_', 'call $removed.primitiveLong_'), glue));
  assert.throws(() => inspectWasm(wat.replaceAll('struct.set $c.scala.scalanative.jmh.examples.IntegrityValue', 'removed.store $c.scala.scalanative.jmh.examples.IntegrityValue'), glue));
  assert.throws(() => inspectWasm(wat.replaceAll('call $f.scala.benchmark.internal.BlackholeSink$.consume', 'drop ;; removed sink .consume').replaceAll('call $f.org.openjdk.jmh.infra.Blackhole.consume', 'drop ;; removed sink .consume'), glue));
  assert.throws(() => inspectWasm(wat.replaceAll('System$NanoTime$', 'removed_clock'), glue), /Clock reads missing/);
  result.rejectsRemovedWorkPayloadConsumptionAndClocks = true;
  fs.writeFileSync(path.join(directory, 'inspection.json'), JSON.stringify(result, null, 2));
}
