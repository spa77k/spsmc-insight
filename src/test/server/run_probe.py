#!/usr/bin/env python3
"""Build and run the probe in a fresh, disconnected Docker server; preserve all artifacts."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import uuid
from decimal import Decimal

ROOT = Path(__file__).resolve().parents[3]
SOURCE = Path(sys.argv[1]).resolve()
os.chdir(ROOT)
subprocess.run(['mvn', '-q', 'test', 'package', 'dependency:build-classpath',
                '-Dmdep.outputFile=target/test-classpath.txt'], check=True)
run = Path(tempfile.mkdtemp(prefix='jobs-probe-', dir=ROOT / 'target'))
plugins = run / 'plugins'
plugins.mkdir()
for name in ['paper-26.1.2-74.jar', 'eula.txt']:
    shutil.copy2(SOURCE / name, run / name)
for name in ['libraries', 'versions', 'cache']:
    shutil.copytree(SOURCE / name, run / name)
for name in ['4216.jar', '87610.jar', 'VaultUnlocked-2.20.2.jar', 'EssentialsX-2.22.0.jar',
             'LuckPerms-Bukkit-5.5.71.jar']:
    shutil.copy2(SOURCE / 'plugins' / name, plugins / name)
shutil.copy2(ROOT / 'target/spsmc-insight-0.1.0.jar', plugins / 'SPSMCInsight.jar')
(plugins / 'Jobs').mkdir()
shutil.copytree(SOURCE.parent / 'plugins/Jobs/jobs', plugins / 'Jobs/jobs')
(plugins / 'Jobs/generalConfig.yml').write_text('economy-async: false\nEconomy:\n  PaymentMethods:\n    Money: true\n    Points: false\n    Exp: true\n')
(plugins / 'SPSMCInsight').mkdir()
(plugins / 'SPSMCInsight/config.yml').write_text('backfill:\n  enabled: false\nexport:\n  enabled: false\nretention:\n  enabled: false\n')
(run / 'server.properties').write_text('server-port=25585\nonline-mode=true\nenable-rcon=false\nview-distance=2\nsimulation-distance=2\nmax-players=1\n')
cp = (ROOT / 'target/test-classpath.txt').read_text().strip() + os.pathsep + str(ROOT / 'target/classes')
cp += os.pathsep + os.pathsep.join(str(p) for p in (SOURCE / 'plugins').glob('*.jar'))
classes = run / 'probe-classes'
classes.mkdir()
java_home = os.environ.get('JAVA_HOME')
def java_tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name
subprocess.run([java_tool('javac'), '--release', '21', '-cp', cp, '-d', str(classes),
                str(ROOT / 'src/test/server/JobsAuditProbe.java')], check=True)
(classes / 'plugin.yml').write_text('name: JobsAuditProbe\nversion: 1\nmain: dev.spa.insight.probe.JobsAuditProbe\napi-version: "1.20"\ndepend: [Jobs, Essentials, SPSMCInsight]\n')
subprocess.run([java_tool('jar'), 'cf', str(plugins / 'JobsAuditProbe.jar'), '-C', str(classes), '.'], check=True)
container = 'insight-' + run.name
subprocess.run(['docker', 'run', '-d', '--name', container, '--hostname', 'insight-probe', '--network', 'none',
                '-v', str(run) + ':/data', '-w', '/data', '--entrypoint', 'java',
                'spsmc-infra:java25', '-Xms512M', '-Xmx1536M', '-jar', 'paper-26.1.2-74.jar', '--nogui'], check=True)
print('Probe artifacts:', run, flush=True)
for _ in range(180):
    state = subprocess.check_output(['docker', 'inspect', '--format', '{{.State.Running}}', container], text=True).strip()
    if state == 'false':
        break
    time.sleep(1)
else:
    raise RuntimeError('Server did not finish; inspect preserved container ' + container)
logs = subprocess.check_output(['docker', 'logs', container], stderr=subprocess.STDOUT, text=True)
(run / 'probe.log').write_text(logs)
assert 'PROBE FAILED' not in logs, 'Probe execution failed; inspect probe.log'
assert 'PROBE balances=' in logs
assert 'Jobs audit observation failed' not in logs and 'Jobs audit DB write failed' not in logs
con = sqlite3.connect(plugins / 'SPSMCInsight/insight.db')
con.row_factory = sqlite3.Row
rows = [dict(r) for r in con.execute('SELECT * FROM jobs_audit')]
def user_id(name):
    return str(uuid.UUID(bytes=hashlib.md5(('InsightAuditProbe-' + name).encode()).digest(), version=3))
def records(name, phase=None):
    return [r for r in rows if r['uuid'] == user_id(name) and (phase is None or r['phase'] == phase)]
for name, amount in [('normal', '1.25'), ('changed', '0.75'), ('negative', '-2.5')]:
    receipts = records(name, 'balance')
    assert len(receipts) == 1 and receipts[0]['status'] == 'balance_verified', (name, receipts)
    assert Decimal(receipts[0]['verified_delta']) == Decimal(amount)
assert len(records('overlap', 'balance')) == 2
assert all(r['status'] == 'ambiguous' and r['verified_delta'] is None for r in records('overlap'))
assert len(records('async', 'balance')) == 1 and records('async')[0]['status'] == 'unverified_async'
assert not records('unrelated') and not records('failure', 'balance')
assert len(records('cancelled', 'payment')) == 1 and records('cancelled')[0]['status'] == 'cancelled'
assert not records('cancelled', 'balance')
assert len(records('pre')) == 1 and records('pre')[0]['status'] == 'cancelled'
assert Decimal(records('pre')[0]['planned_money']) == 10 and Decimal(records('pre')[0]['event_money']) == Decimal('2.5')
assert records('pre')[0]['action'] == 'BREAK' and records('pre')[0]['target'] == 'DIAMOND_ORE'
assert records('pre')[0]['world'] == 'world'
assert all(r['limit_reduced'] is None for r in rows)
normal = records('normal')[0]
assert normal['session_id'] is not None
assert con.execute('SELECT uuid FROM sessions WHERE id=?', (normal['session_id'],)).fetchone()[0] == user_id('normal')
exports = list((plugins / 'SPSMCInsight/exports').glob('*/jobs-summary.json'))
assert len(exports) == 1
summary = json.loads(exports[0].read_text(), parse_float=Decimal)
assert summary['income_by_job'] is None
assert sum(Decimal(str(p['verified_net_balance_delta'])) for p in summary['players']) == Decimal('-0.5')
assert len((exports[0].parent / 'jobs-audit.jsonl').read_text().splitlines()) == len(rows)
report = {'result': 'PASS', 'container': container, 'directory': str(run),
          'audit_rows': len(rows), 'summary': summary,
          'limits': ['No real client mining or Jobs limit-reduction E2E test', 'Balance evidence, not transaction receipt']}
(run / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str))
print(json.dumps({'result': 'PASS', 'audit_rows': len(rows), 'directory': str(run)}, ensure_ascii=False), flush=True)
