import { DatabaseSync } from 'node:sqlite';
import { createHash, randomBytes } from 'node:crypto';
export const hash = x => createHash('sha256').update(x).digest('hex');
const fail = (message,status=409) => Object.assign(new Error(message),{status});
/** One SQLite writer; commit before upstream delivery. No private provider keys enter this DB. */
export class Store {
  constructor(path=':memory:') {
    this.db=new DatabaseSync(path);
    this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000;
      CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, token_hash TEXT UNIQUE, revoked INTEGER DEFAULT 0);
      CREATE TABLE IF NOT EXISTS pairing(code_hash TEXT PRIMARY KEY, expires INTEGER, attempts INTEGER DEFAULT 0);
      CREATE TABLE IF NOT EXISTS requests(device TEXT,id TEXT,session TEXT,text TEXT,run TEXT,created INTEGER,updated INTEGER,seq INTEGER DEFAULT 0,result TEXT, PRIMARY KEY(device,id));
      CREATE UNIQUE INDEX IF NOT EXISTS request_run ON requests(run) WHERE run IS NOT NULL;
      CREATE TABLE IF NOT EXISTS events(device TEXT,id TEXT,seq INTEGER,body TEXT,PRIMARY KEY(device,id,seq));`);
  }
  active() {return this.db.prepare("SELECT * FROM requests WHERE run IS NOT NULL AND (result IS NULL OR json_extract(result,'$.state') NOT IN ('completed','failed','cancelled','interrupted')) ORDER BY updated LIMIT 256").all();}
  pending() {return this.db.prepare("SELECT r.* FROM requests r JOIN devices d ON d.id=r.device WHERE d.revoked=0 AND r.run IS NULL AND (r.result IS NULL OR json_extract(r.result,'$.state') IN ('accepted','retrying')) ORDER BY r.created LIMIT 16").all();}
  pairCode(ttl=600000) {
    const code=randomBytes(6).toString('hex').toUpperCase();
    this.db.prepare('INSERT INTO pairing(code_hash,expires) VALUES(?,?)').run(hash(code),Date.now()+ttl);
    return code;
  }
  redeem(code) {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const row=this.db.prepare('SELECT * FROM pairing WHERE code_hash=?').get(hash(code));
      if(!row || row.expires<Date.now()) throw fail('Pairing code expired or invalid',403);
      this.db.prepare('DELETE FROM pairing WHERE code_hash=?').run(hash(code));
      const id='device_'+randomBytes(12).toString('hex'),token=randomBytes(32).toString('base64url');
      this.db.prepare('INSERT INTO devices(id,token_hash) VALUES(?,?)').run(id,hash(token));
      this.db.exec('COMMIT'); return {deviceId:id,token};
    } catch(e) {this.db.exec('ROLLBACK');throw e;}
  }
  authenticate(token) {return this.db.prepare('SELECT id FROM devices WHERE token_hash=? AND revoked=0').get(hash(token))?.id;}
  revoke(id) {this.db.prepare('UPDATE devices SET revoked=1 WHERE id=?').run(id);}
  rotate(id) {const token=randomBytes(32).toString('base64url'); this.db.prepare('UPDATE devices SET token_hash=? WHERE id=? AND revoked=0').run(hash(token),id);return token;}
  get(device,id) {return this.db.prepare('SELECT * FROM requests WHERE device=? AND id=?').get(device,id);}
  byRun(device,run) {return this.db.prepare('SELECT * FROM requests WHERE device=? AND run=?').get(device,run);}
  reserve(device,b) {
    const old=this.get(device,b.id);
    if(old) {if(old.session!==b.sessionId || old.text!==b.text) throw fail('Request ID already used for different content');return old;}
    const active=this.db.prepare("SELECT id FROM requests WHERE device=? AND session=? AND (result IS NULL OR json_extract(result,'$.state') NOT IN ('completed','failed','cancelled','interrupted'))").get(device,b.sessionId);
    if(active) throw fail('Another request is active in this conversation');
    this.db.prepare('INSERT INTO requests(device,id,session,text,created,updated) VALUES(?,?,?,?,?,?)').run(device,b.id,b.sessionId,b.text,Date.now(),Date.now());
    return this.get(device,b.id);
  }
  publish(device,id,body) {
    const row=this.get(device,id); if(!row) throw fail('Unknown request',404);
    const normalized={...body,id}; delete normalized.seq;
    const previous=row.result?JSON.parse(row.result):null;
    if(previous) {const p={...previous};delete p.seq;if(JSON.stringify(p)===JSON.stringify(normalized))return previous;}
    const result={...normalized,seq:row.seq+1};
    this.db.exec('BEGIN IMMEDIATE');
    try {
      this.db.prepare('UPDATE requests SET run=COALESCE(?,run),result=?,seq=?,updated=? WHERE device=? AND id=?').run(body.runId||null,JSON.stringify(result),result.seq,Date.now(),device,id);
      this.db.prepare('INSERT INTO events VALUES(?,?,?,?)').run(device,id,result.seq,JSON.stringify(result));
      this.db.exec('COMMIT');return result;
    }catch(e){this.db.exec('ROLLBACK');throw e;}
  }
  events(device,id,after=0) {return this.db.prepare('SELECT body FROM events WHERE device=? AND id=? AND seq>? ORDER BY seq LIMIT 100').all(device,id,after).map(r=>JSON.parse(r.body));}
  close(){this.db.close();}
}
