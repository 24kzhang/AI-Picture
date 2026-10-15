import {dirname,delimiter,isAbsolute} from 'node:path'

/** Studio invokes FFmpeg by name even when the renderer is configured with absolute paths. */
export function mediaEnvironment(base:NodeJS.ProcessEnv,executables:string[]):NodeJS.ProcessEnv {
  const environment={...base}
  const keys=Object.keys(environment).filter(key=>key.toLowerCase()==='path')
  const pathKey=keys[0]||'PATH'
  const previous=keys.map(key=>environment[key]).filter(Boolean).join(delimiter)
  for(const key of keys)delete environment[key]
  const directories=[...new Set(executables.filter(isAbsolute).map(path=>dirname(path)))]
  environment[pathKey]=[...directories,previous].filter(Boolean).join(delimiter)
  return environment
}
