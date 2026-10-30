import { mkdirSync, readFileSync, openSync, writeSync, fsyncSync, closeSync, renameSync } from 'node:fs';
import { join } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';

export class PersistentDeliveryJournal {
  constructor(directory) { this.directory=directory;this.inFlight=new Map();mkdirSync(directory,{recursive:true}); }
  file(key){return join(this.directory,createHash('sha256').update(key).digest('hex')+'.json');}
  read(key){try{return JSON.parse(readFileSync(this.file(key),'utf8'));}catch(e){if(e.code==='ENOENT')return null;throw e;}}
  write(key,value){
    const temp=this.file(key)+'.'+randomUUID()+'.tmp';const fd=openSync(temp,'wx',0o600);
    try{writeSync(fd,JSON.stringify(value));fsyncSync(fd);}finally{closeSync(fd);}
    renameSync(temp,this.file(key));
    // Linux bind/volume directory fsync makes the rename durable before invoking QQ.
    if(process.platform!=='win32'){const dir=openSync(this.directory,'r');try{fsyncSync(dir);}finally{closeSync(dir);}}
  }
  status(key){const value=this.read(key);if(!value)return {status:'missing'};return {...value,status:value.status==='sending'?'unknown':value.status};}
  async run(key,hash,action){
    const current=this.inFlight.get(key);
    if(current){if(current.hash!==hash)throw Object.assign(new Error('payload conflict'),{outcome:'conflict'});return {result:await current.promise,repeated:true};}
    const saved=this.read(key);
    if(saved&&saved.payload_hash!==hash)throw Object.assign(new Error('payload conflict'),{outcome:'conflict'});
    if(saved?.status==='sent')return {result:{id:saved.external_id},repeated:true};
    if(saved&&saved.status!=='failed')throw Object.assign(new Error('delivery outcome unknown'),{outcome:'unknown'});
    const promise=(async()=>{
      this.write(key,{status:'sending',payload_hash:hash,updated_at:new Date().toISOString()});
      try{
        const result=await action();
        this.write(key,{status:'sent',payload_hash:hash,external_id:result?.id||result?.message_id||null,updated_at:new Date().toISOString()});return result;
      }catch(error){
        this.write(key,{status:'unknown',payload_hash:hash,updated_at:new Date().toISOString()});
        throw Object.assign(new Error('delivery outcome unknown'),{outcome:'unknown'});
      }
    })();
    this.inFlight.set(key,{hash,promise});
    try{return {result:await promise,repeated:false};}finally{this.inFlight.delete(key);}
  }
}
