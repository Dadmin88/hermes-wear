#!/usr/bin/env node
// Run locally as the watch service user. Do not paste device credentials into chat.
import {Store} from './store.mjs';
const store=new Store(process.env.WATCH_STORE_PATH||'/var/lib/hermes-wear/relay.sqlite');
try {
 const [op,id]=process.argv.slice(2);
 if(op==='create') console.log('One-time pairing code (10 minutes): '+store.pairCode());
 else if(op==='revoke' && /^device_[a-f0-9]{24}$/.test(id||'')) {store.revoke(id);console.log('Device revoked');}
 else {console.error('Usage: pair-admin.mjs create | revoke device_ID');process.exitCode=2;}
} finally {store.close();}
