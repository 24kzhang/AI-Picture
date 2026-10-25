import {rows,db} from '../src/db.js'
try{console.log(await rows("SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'video_%'"))}finally{await db.end()}
