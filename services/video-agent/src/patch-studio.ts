import {patchStudio} from './studio.js'
import {patchMediaExecution} from './hypit.js'
await patchStudio()
await patchMediaExecution()
console.log('Hypit 0.2.16 Studio 与单帧合成补丁已校验')
