// The real HTTP/journal adapter with an in-process fake transport. No QQ SDK/network.
import {PersistentDeliveryJournal} from '/gateway/persistent-delivery.mjs';
import {createGatewayServer} from '/gateway/gateway-http.mjs';
import {randomUUID} from 'node:crypto';
createGatewayServer({token:process.env.QQ_GATEWAY_TOKEN,journal:new PersistentDeliveryJournal('/deliveries'),ready:()=>true,sendText:async()=>({id:'offline-'+randomUUID()})}).listen(3000,'0.0.0.0');
