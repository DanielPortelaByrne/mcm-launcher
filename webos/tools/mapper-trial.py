"""Run the reviewed MCM mapper for at most five minutes, then release input."""
import subprocess
from pathlib import Path

root=Path('/var/lib/mcm-home')
command=['python3','-u',str(root/'mapper/runtime/ir-mapper.py'),
         '--config',str(root/'mapper/magic_mapper_config.json'),
         '--state-dir','/tmp/mcm-mapper',
         '--app-dir','/media/developer/apps/usr/palm/applications/com.daniel.mcm.home',
         '--no-start-delay']
with (root/'mapper.log').open('w') as log:
    process=subprocess.Popen(command,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT)
    (root/'mapper.pid').write_text(str(process.pid))
    try:
        process.wait(timeout=300)
    except subprocess.TimeoutExpired:
        process.terminate()
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:process.kill();process.wait()
    finally:
        if process.poll() is None:process.terminate();process.wait()
