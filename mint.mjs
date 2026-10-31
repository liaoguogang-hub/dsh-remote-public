import { readFileSync } from 'node:fs'
import { createHmac, createHash } from 'node:crypto'
const yaml = readFileSync(process.env.USERPROFILE + '\\.dsh\\.credentials.yaml','utf8').split(/\r?\n/)
let secret=null
for (let i=0;i<yaml.length;i++){
  if (/client-connection\/browser-session:/.test(yaml[i])) {
    for (let j=i;j<Math.min(i+8,yaml.length);j++){
      const m=yaml[j].match(/^\s*secret:\s*(\S+)\s*$/); if(m){secret=m[1];break}
    }
  }
  if(secret) break
}
if(!secret){console.error('secret not found');process.exit(1)}
const authority=process.argv[2]
// Optional validity in days (default 30). DSH only verifies the HMAC and `expiresAt`, so a longer
// window is accepted — use it to stop re-minting (and re-packaging the APK) every month.
const days = Number(process.argv[3] || 30)
if (!Number.isFinite(days) || days <= 0) { console.error('days must be a positive number'); process.exit(1) }
const b64=b=>Buffer.from(b).toString('base64').replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'')
const now=Date.now()
const body=b64(Buffer.from(JSON.stringify({version:1,authority,issuedAt:now,expiresAt:now+days*24*3600*1000})))
const sig=b64(createHmac('sha256',Buffer.from(secret,'base64url')).update(body).digest())
const name='dsh-auth-'+b64(createHash('sha256').update(authority).digest())
console.log(name+'=v1.'+body+'.'+sig)
