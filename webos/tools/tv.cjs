const { Client } = require('ssh2');
const fs = require('node:fs');
const path = require('node:path');
const request = JSON.parse(fs.readFileSync(0, 'utf8'));
const host = '192.168.0.35';
const fingerprintPath = path.join(__dirname, 'tv-host-fingerprint.txt');
const client = new Client();
client.on('error', e => { console.error(e.message); process.exitCode = 1; });
client.on('ready', () => {
  if (request.action === 'upload' || request.action === 'download') {
    client.sftp((err, sftp) => {
      if (err) { console.error(err.message); client.end(); process.exitCode = 1; return; }
      let files = request.files.slice();
      const next = () => {
        const f = files.shift();
        if (!f) { client.end(); return; }
        const transfer = request.action === 'upload' ? sftp.fastPut.bind(sftp) : sftp.fastGet.bind(sftp);
        const from = request.action === 'upload' ? f.local : f.remote;
        const to = request.action === 'upload' ? f.remote : f.local;
        transfer(from, to, err => {
          if (err) { console.error(err.message); client.end(); process.exitCode = 1; return; }
          console.log((request.action === 'upload' ? 'Uploaded ' : 'Downloaded ') + f.remote); next();
        });
      };
      next();
    });
  } else {
    client.exec(request.command, (err, stream) => {
      if (err) { console.error(err.message); client.end(); process.exitCode = 1; return; }
      stream.on('data', d => process.stdout.write(d));
      stream.stderr.on('data', d => process.stderr.write(d));
      stream.on('close', code => { process.exitCode = code || 0; client.end(); });
    });
  }
});
client.connect({ host, port: 22, username: 'root', password: process.env.LG_TV_PASSWORD || 'alpine',
  readyTimeout: 12000, hostHash: 'sha256', hostVerifier: hash => {
    if (fs.existsSync(fingerprintPath)) return fs.readFileSync(fingerprintPath, 'utf8').trim() === hash;
    fs.writeFileSync(fingerprintPath, hash); return true;
  }
});
