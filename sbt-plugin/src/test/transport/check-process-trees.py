import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

config = json.loads(Path(sys.argv[1]).read_text())
root = Path(config['evidence']) / 'process-trees'
root.mkdir(exist_ok=True)
records = []

def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False

for repeat in range(3):
    for action in ['finish', 'crash', 'hang', 'owner-kill']:
        case = f'{action}-{repeat}'
        marker = root / (case + '.pids')
        result = root / (case + '.json')
        tmp = root / (case + '-tmp')
        tmp.mkdir()
        args = ['ChildTreeBenchmark', '-p', 'marker=' + str(marker), '-p', 'action=' + ('hang' if action == 'owner-kill' else action),
                '-to', '5s' if action == 'owner-kill' else '500ms', '-foe', 'true', '-rf', 'json', '-rff', str(result)]
        pids = []
        with open(root / (case + '.log'), 'wb') as log:
            child = subprocess.Popen(config['command'] + args, stdout=log, stderr=log, env={**os.environ, 'TMPDIR': str(tmp)})
            try:
                deadline = time.monotonic() + 5
                while (not marker.exists() or len(marker.read_text().split()) != 2) and child.poll() is None and time.monotonic() < deadline:
                    time.sleep(.01)
                assert marker.exists(), (case, 'child never initialized')
                pids = list(map(int, marker.read_text().split()))
                assert len(pids) == 2 and all(pid > 1 for pid in pids), (case, pids)
                if action == 'owner-kill':
                    child.kill()
                code = child.wait(timeout=8)
                assert code == 0 if action == 'finish' else code != 0, (case, code)
                deadline = time.monotonic() + 3
                while (any(alive(pid) for pid in pids) or list(tmp.iterdir())) and time.monotonic() < deadline:
                    time.sleep(.02)
                assert not any(alive(pid) for pid in pids), (case, 'descendant survived', pids)
                assert not list(tmp.iterdir()), (case, 'transport directory leaked', list(tmp.iterdir()))
                rows = json.loads(result.read_text()) if result.exists() and result.read_text().strip() else []
                assert len(rows) == (1 if action == 'finish' else 0), (case, rows)
                records.append({'case': case, 'exit': code, 'descendants': pids, 'results': len(rows)})
            finally:
                if child.poll() is None:
                    child.kill()
                    child.wait(timeout=3)
                for pid in pids:
                    if alive(pid):
                        os.kill(pid, signal.SIGKILL)

# Block framework listing output. No worker or user fixture is required.
with open(root / 'console-listing.stderr', 'wb') as errors:
    child = subprocess.Popen(config['command'] + ['--suite', 'microbench.examples.ConsoleInventory$', '-l', '-to', '500ms'],
                             stdout=subprocess.PIPE, stderr=errors)
    try:
        assert child.wait(timeout=8) == 1, 'Blocked framework console did not fail'
    finally:
        if child.poll() is None:
            child.kill()
            child.wait(timeout=3)
        child.stdout.close()
records.append({'case': 'blocked-framework-listing', 'exit': 1})

for flag in ['-rff', '-o']:
    fifo = root / ('output-' + flag.removeprefix('-') + '.pipe')
    os.mkfifo(fifo)
    marker = root / ('invalid-' + flag.removeprefix('-') + '.pids')
    with open(root / (flag.removeprefix('-') + '-preflight.log'), 'wb') as log:
        child = subprocess.Popen(config['command'] + ['ChildTreeBenchmark', '-p', 'marker=' + str(marker), flag, str(fifo), '-foe', 'true'], stdout=log, stderr=log)
        try:
            assert child.wait(timeout=5) == 1, 'Non-regular output destination was accepted'
            assert not marker.exists(), 'Invalid output destination initialized a fixture'
        finally:
            if child.poll() is None:
                child.kill()
                child.wait(timeout=3)
    fifo.unlink()
    records.append({'case': 'reject-nonregular-' + flag, 'exit': 1})
(root / 'observations.json').write_text(json.dumps(records, indent=2) + '\n')
print('15 process-tree, console and repeated cancellation cases passed')
