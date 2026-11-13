import test from 'node:test';
import assert from 'node:assert/strict';
import { ProgressDeadline } from '../../main/resources/scala-benchmark/progress-deadline.mjs';

test('stale, partial and malformed progress cannot renew a lease', () => {
  let now = 0n;
  const deadline = new ProgressDeadline('1', () => now);
  assert.equal(deadline.receive('3 0 10\n'), true);
  now = 9n;
  assert.equal(deadline.receive('3 0 1000\n'), false);
  for (const text of ['3 1 100', '3 1 0\n', '3 -1 10\n', '2 1 100\n', '3  1 10\n',
    '3 9223372036854775808 10\n', '3 1 9223372036854775808\n']) {
    assert.equal(deadline.receive(text), false, text);
  }
  now = 10n;
  assert.equal(deadline.expired(), true);
  assert.equal(deadline.receive('3 1 10\n'), true);
  now = 19n;
  assert.equal(deadline.expired(), false);
  now = 20n;
  assert.equal(deadline.expired(), true);
});

test('wall-clock changes have no effect and budgets above 2^53 stay exact', () => {
  let now = 0n;
  const deadline = new ProgressDeadline('1', () => now);
  const original = Date.now;
  try {
    const budget = 9007199254740993n;
    assert.equal(deadline.receive(`3 ${budget} ${budget}\n`), true);
    Date.now = () => Number.MAX_SAFE_INTEGER;
    now = budget - 1n;
    assert.equal(deadline.expired(), false);
    Date.now = () => -Number.MAX_SAFE_INTEGER;
    now = budget;
    assert.equal(deadline.expired(), true);
  } finally { Date.now = original; }
});
