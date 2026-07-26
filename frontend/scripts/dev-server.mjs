import { networkInterfaces } from 'node:os';
import { createServer } from 'vite';

const DEFAULT_PORT = 5173;
const port = Number(process.env.PORT || process.env.VITE_PORT || DEFAULT_PORT);
const networkHost = process.env.VITE_NETWORK_HOST || findPreferredNetworkHost();

const server = await createServer({
  server: {
    host: '0.0.0.0',
    port,
  },
});

await server.listen();

const address = server.httpServer?.address();
const actualPort = typeof address === 'object' && address ? address.port : port;

console.log('\n  VITE dev server ready');
console.log(`  ➜  Local:   http://localhost:${actualPort}/`);
if (networkHost) {
  console.log(`  ➜  Network: http://${networkHost}:${actualPort}/`);
}
console.log('  ➜  press h + enter to show help\n');

function findPreferredNetworkHost() {
  const addresses = Object.values(networkInterfaces())
    .flatMap((items) => items || [])
    .filter((item) => item.family === 'IPv4' && !item.internal)
    .map((item) => item.address)
    .filter((address) => !address.startsWith('169.254.') && !address.startsWith('198.18.'));

  return (
    addresses.find((address) => address.startsWith('192.168.') && !address.endsWith('.1')) ||
    addresses.find((address) => address.startsWith('10.')) ||
    addresses.find((address) => /^172\.(1[6-9]|2\d|3[0-1])\./.test(address)) ||
    addresses.find((address) => address.startsWith('192.168.')) ||
    addresses[0] ||
    ''
  );
}
