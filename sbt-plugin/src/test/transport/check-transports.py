"""Linked host/protocol faults under an independent process watchdog."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time

config = json.loads(Path(sys.argv[1]).read_text())
root = Path(config['evidence'])
backend = config['backend']
command = config['command']
observations = []


def check(mode, blocked=False):
    ledger = root / (mode + '.ledger')
    report = root / (mode + '.json')
    tmp = root / (mode + '-tmp')
    tmp.mkdir()
    args = ['(A0SequenceHealthyBenchmark|ASequenceBenchmark|ZSequenceRecoveryBenchmark)' if blocked else '(ASequenceBenchmark|ZSequenceRecoveryBenchmark)',
            '-p', 'ledger=' + str(ledger), '-p', 'failFork=' + ('1' if blocked else '99'),
            '-p', 'boundary=' + ('output' if blocked else 'work'), '-bm', 'ss', '-t', '1', '-wi', '0', '-i', '2', '-f', '1' if blocked else '2', '-wf', '0',
            '-to', '500ms', '-foe', 'false', '-rf', 'json', '-rff', str(report)]
    env = {**os.environ, 'TMPDIR': str(tmp), 'SCALA_BENCHMARK_TEST_TRANSPORT': '' if blocked else mode}
    started = time.monotonic()
    with open(root / (mode + '.stderr'), 'wb') as errors, open(root / (mode + '.stdout'), 'wb') as output:
        child = subprocess.Popen(command + args, stdout=subprocess.PIPE if blocked else output, stderr=errors, env=env)
        try:
            code = child.wait(timeout=15)
            assert code == (1 if blocked else 0), (mode, code, (root / (mode + '.stderr')).read_text())
        finally:
            if child.poll() is None:
                child.kill()
                child.wait(timeout=3)
            if blocked:
                child.stdout.close()
    rows = json.loads(report.read_text()) if report.exists() and report.read_text().strip() else []
    expected = 'A0SequenceHealthyBenchmark.execute' if blocked else 'ZSequenceRecoveryBenchmark.execute'
    assert len(rows) == 1 and rows[0]['benchmark'].endswith(expected), (mode, rows)
    assert rows[0]['primaryMetric']['rawData'] and all(len(fork) == 2 for fork in rows[0]['primaryMetric']['rawData'])
    assert int(ledger.read_text()) == 1, (mode, ledger.read_text())
    assert not any(tmp.iterdir()), (mode, 'transport files leaked', list(tmp.iterdir()))
    observations.append({'case': mode, 'exit': code, 'result': expected, 'elapsed': time.monotonic() - started})


for fault in ['truncate', 'incomplete', 'trailing'] + (['nonregular'] if os.name != 'nt' else []) + (['kill-write'] if backend != 'native' else []):
    check(fault)
check('blocked-output', blocked=True)

if backend == 'native':
    # Drive the real serializer through a FIFO larger than the kernel pipe buffer.
    # Stop reading after one prefix, then kill the worker while its write is blocked.
    directory = root / 'serialization-interruption'
    directory.mkdir()
    request = directory / 'request.properties'
    request.write_text('suite=scala.benchmark.examples.SerializationProbe$\nbenchmark=0\nmode=SingleShotTime\nbulk.count=0\n-to=1000000000ns\n')
    fifo = directory / 'result.pipe'
    os.mkfifo(fifo)
    prefix = bytearray()
    ready = threading.Event()
    release = threading.Event()

    def reader():
        with open(fifo, 'rb', buffering=0) as stream:
            prefix.extend(stream.read(64))
            ready.set()
            release.wait(timeout=10)

    receiver = threading.Thread(target=reader, daemon=True)
    receiver.start()
    with open(directory / 'worker.log', 'wb') as log:
        child = subprocess.Popen(command + ['--benchmark-worker-v3', str(request), str(fifo), str(directory / 'deadline')],
                                 stdin=subprocess.PIPE, stdout=log, stderr=log)
        try:
            assert ready.wait(timeout=5), 'Serializer did not publish its prefix'
            assert child.poll() is None, 'Serializer unexpectedly finished before interruption'
            time.sleep(.05)
            child.kill()
            code = child.wait(timeout=3)
            assert code != 0 and len(prefix) == 64
        finally:
            release.set()
            if child.poll() is None:
                child.kill()
                child.wait(timeout=3)
            child.stdin.close()
    receiver.join(timeout=2)
    fifo.unlink()
    (directory / 'partial.bin').write_bytes(prefix)
    observations.append({'case': 'killed-during-binary-serialization', 'exit': code, 'prefixBytes': len(prefix)})

(root / 'transport-observations.json').write_text(json.dumps(observations, indent=2) + '\n')
print(backend + ': linked corruption, interrupted serialization and blocked output checks passed')
