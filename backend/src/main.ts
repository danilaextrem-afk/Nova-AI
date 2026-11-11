import { buildApp } from './server.js';
import { config } from './config.js';
const app=await buildApp();
await app.listen({port:config.port,host:'0.0.0.0'});
for(const signal of ['SIGTERM','SIGINT']) process.once(signal,()=>{void app.close();});
