import { pathToFileURL } from 'node:url';
globalThis.__microbenchHost = { stringify: JSON.stringify, parse: JSON.parse };
await import(pathToFileURL(process.argv[2]).href);
